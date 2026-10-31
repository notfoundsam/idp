package idp.sharedkernel.outbox

import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.migration.query
import idp.sharedkernel.migration.update
import idp.sharedkernel.scheduling.JobLocks
import idp.sharedkernel.scheduling.JobScheduler
import idp.sharedkernel.scheduling.ScheduledJob
import idp.sharedkernel.scheduling.TenantCleanupJob
import idp.sharedkernel.scheduling.TenantJobRunner
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.MovableClock
import idp.sharedkernel.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.UUID

private val RETENTION: Duration = Duration.ofDays(7)
private val TTL: Duration = Duration.ofDays(7)

private class Cleanup {
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
    val worker = database.role("worker_runtime")
    val clock = MovableClock()
    val platform = PlatformTransaction(worker)
    val runner = TenantJobRunner(platform, TenantTransaction(worker))

    fun scheduler(job: ScheduledJob) = JobScheduler(listOf(job), JobLocks(platform, clock))

    fun tenant(): UUID {
        val id = UUID.randomUUID()
        owner.update("INSERT INTO platform.tenants (id, name, status) VALUES ('$id', 'tenant', 'ACTIVE')")
        TenantSchemaFactory(owner).create(id)
        TenantMigrationRunner(owner).migrateTenant(id)
        return id
    }

    fun outboxRow(
        status: String,
        age: Duration,
    ): UUID {
        val id = UUID.randomUUID()
        owner.update(
            """
            INSERT INTO platform.outbox
                (id, aggregate_type, aggregate_id, aggregate_version, event_type, correlation_id, status, available_at)
            VALUES
                ('$id', 'user', gen_random_uuid(), 1, 'created', gen_random_uuid(), '$status',
                 now() - interval '${age.toSeconds()} seconds')
            """,
        )
        return id
    }

    fun outboxRows(): List<UUID> =
        owner
            .query("SELECT coalesce(string_agg(id::text, ','), '') FROM platform.outbox")
            .split(',')
            .filter { it.isNotEmpty() }
            .map(UUID::fromString)

    fun processed(
        tenant: UUID,
        age: Duration,
    ) {
        owner.update(
            """
            INSERT INTO "${TenantSchema(tenant).name}".processed_events (event_id, handler, processed_at)
            VALUES (gen_random_uuid(), 'handler', now() - interval '${age.toSeconds()} seconds')
            """,
        )
    }

    fun processedRows(tenant: UUID): String = owner.query("""SELECT count(*) FROM "${TenantSchema(tenant).name}".processed_events""")
}

class OutboxCleanupTest :
    FunSpec({
        context("outbox") {
            test("only finished events past the retention are removed") {
                Cleanup().run {
                    outboxRow("DONE", RETENTION.plusHours(1))
                    val keptRows =
                        listOf(
                            outboxRow("DONE", RETENTION.minusHours(1)),
                            outboxRow("PENDING", RETENTION.plusHours(1)),
                            outboxRow("DEAD", RETENTION.plusHours(1)),
                        )

                    scheduler(OutboxCleanupJob(platform, clock, RETENTION)).tick()

                    outboxRows() shouldContainExactlyInAnyOrder keptRows
                }
            }
        }

        context("processed_events") {
            test("rows past the TTL are removed in every tenant") {
                Cleanup().run {
                    val a = tenant()
                    val b = tenant()
                    listOf(a, b).forEach {
                        processed(it, TTL.plusHours(1))
                        processed(it, TTL.minusHours(1))
                    }
                    val cleanup = ProcessedEventsCleanup(Duration.ofHours(3), TTL)

                    scheduler(TenantCleanupJob(cleanup, runner, clock)).tick()

                    processedRows(a) shouldBe "1"
                    processedRows(b) shouldBe "1"
                }
            }

            test("a TTL within the redelivery window is rejected, because a redelivered event would run twice") {
                shouldThrow<IllegalArgumentException> { ProcessedEventsCleanup(TTL, TTL) }
            }

            test("the default TTL outlasts the redelivery window of the default dispatcher") {
                Cleanup().run {
                    val dispatcher = OutboxDispatcher(platform, IdempotentHandler(TenantTransaction(worker)), emptyList())

                    ProcessedEventsCleanup(dispatcher.redeliveryWindow)
                }
            }
        }
    })
