package idp.sharedkernel.scheduling

import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.migration.query
import idp.sharedkernel.migration.update
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.MovableClock
import idp.sharedkernel.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.concurrent.thread

private val LEASE: Duration = Duration.ofMinutes(10)
private val INTERVAL: Duration = Duration.ofHours(1)
private val SECOND: Duration = Duration.ofSeconds(1)
private const val WAIT_SECONDS = 60L
private const val WORKERS = 4

private class CountingJob(
    override val name: String = "job",
    override val interval: Duration = INTERVAL,
    private val action: (JobLease) -> Unit = {},
) : ScheduledJob {
    val runs = AtomicInteger()

    override fun run(lease: JobLease) {
        runs.incrementAndGet()
        action(lease)
    }
}

private class FixtureCleanup(
    override val name: String = "fixture",
    private val action: (DSLContext, OffsetDateTime) -> Unit,
) : TenantCleanup {
    override fun delete(
        dsl: DSLContext,
        now: OffsetDateTime,
    ) = action(dsl, now)
}

private class Schedule {
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
    val worker = database.role("worker_runtime")
    val clock = MovableClock()
    val locks = locks(worker)
    val runner = TenantJobRunner(PlatformTransaction(worker), TenantTransaction(worker))

    fun locks(source: DataSource) = JobLocks(PlatformTransaction(source), clock, LEASE)

    fun scheduler(
        vararg jobs: ScheduledJob,
        source: DataSource = worker,
    ) = JobScheduler(jobs.toList(), locks(source))

    fun cleanupJob(cleanup: TenantCleanup) = TenantCleanupJob(cleanup, runner, clock, INTERVAL)

    fun tenant(status: String = "ACTIVE"): UUID {
        val id = UUID.randomUUID()
        insertTenant(id, status)
        TenantSchemaFactory(owner).create(id)
        TenantMigrationRunner(owner).migrateTenant(id)
        owner.update("""CREATE TABLE "${TenantSchema(id).name}".fixture (id int)""")
        return id
    }

    fun tenantWithoutSchema(status: String): UUID = UUID.randomUUID().also { insertTenant(it, status) }

    fun fixtureRows(tenant: UUID): String = owner.query("""SELECT count(*) FROM "${TenantSchema(tenant).name}".fixture""")

    fun lease(): JobLease = locks.acquire("job").shouldNotBeNull()

    /** A job body runs inside a tenant transaction, and a second worker has a thread and a connection of its own. */
    fun <T> onAnotherWorker(block: () -> T): T = CompletableFuture.supplyAsync(block).get(WAIT_SECONDS, TimeUnit.SECONDS)

    fun moveClock(by: Duration) {
        clock.offset += by
    }

    private fun insertTenant(
        id: UUID,
        status: String,
    ) {
        owner.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES ('$id', 'tenant', '$status', 'kek-$id')")
    }
}

class TenantJobRunnerTest :
    FunSpec({
        context("locking") {
            test("a second worker on PgBouncer does not start a job the first one is running") {
                Schedule().run {
                    val pooled = database.pooled("worker_runtime")
                    val started = CountDownLatch(1)
                    val finish = CountDownLatch(1)
                    val job =
                        CountingJob {
                            started.countDown()
                            finish.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        }

                    val first = thread(isDaemon = true) { scheduler(job, source = pooled).tick() }
                    started.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
                    scheduler(job, source = pooled).tick()
                    finish.countDown()
                    first.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

                    first.isAlive shouldBe false
                    job.runs.get() shouldBe 1
                }
            }

            test("of the workers that tick together one runs the job") {
                Schedule().run {
                    val pooled = database.pooled("worker_runtime")
                    val job = CountingJob()
                    val start = CyclicBarrier(WORKERS)

                    val workers =
                        List(WORKERS) {
                            val scheduler = scheduler(job, source = pooled)
                            thread(isDaemon = true) {
                                start.await(WAIT_SECONDS, TimeUnit.SECONDS)
                                scheduler.tick()
                            }
                        }
                    workers.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }

                    workers.map { it.isAlive }.toSet() shouldBe setOf(false)
                    job.runs.get() shouldBe 1
                }
            }

            test("a job waits for its interval on every worker") {
                Schedule().run {
                    val job = CountingJob()

                    scheduler(job).tick()
                    scheduler(job).tick()
                    job.runs.get() shouldBe 1

                    moveClock(INTERVAL.minus(SECOND))
                    scheduler(job).tick()
                    job.runs.get() shouldBe 1

                    moveClock(SECOND.multipliedBy(2))
                    scheduler(job).tick()
                    job.runs.get() shouldBe 2
                }
            }

            test("the lease of a dead worker ends") {
                Schedule().run {
                    lease()
                    val job = CountingJob()

                    scheduler(job).tick()
                    job.runs.get() shouldBe 0

                    moveClock(LEASE.plus(SECOND))
                    scheduler(job).tick()
                    job.runs.get() shouldBe 1
                }
            }

            test("a renewed lease outlives its first term") {
                Schedule().run {
                    val lease = lease()
                    val job = CountingJob()

                    moveClock(LEASE.minus(SECOND))
                    lease.renew() shouldBe true
                    moveClock(LEASE.minus(SECOND))
                    scheduler(job).tick()
                    job.runs.get() shouldBe 0

                    moveClock(SECOND.multipliedBy(2))
                    scheduler(job).tick()
                    job.runs.get() shouldBe 1
                }
            }

            test("a late worker neither renews nor releases the lease of the next one") {
                Schedule().run {
                    val late = lease()
                    moveClock(LEASE.plus(SECOND))
                    val next = lease()

                    late.renew() shouldBe false
                    late.release(Duration.ZERO)

                    locks.acquire("job").shouldBeNull()
                    next.renew() shouldBe true
                }
            }

            test("jobs with different names do not share a lock") {
                Schedule().run {
                    lease()
                    val other = CountingJob("other")

                    scheduler(other).tick()

                    other.runs.get() shouldBe 1
                }
            }

            test("a failed job waits for its interval and does not stop the next job") {
                Schedule().run {
                    val failed = CountingJob("failed") { throw IllegalArgumentException("stop") }
                    val next = CountingJob("next")

                    scheduler(failed, next).tick()
                    scheduler(failed, next).tick()

                    failed.runs.get() shouldBe 1
                    next.runs.get() shouldBe 1

                    moveClock(INTERVAL.plus(SECOND))
                    scheduler(failed, next).tick()

                    failed.runs.get() shouldBe 2
                }
            }

            test("two jobs with one name are rejected") {
                Schedule().run {
                    shouldThrow<IllegalArgumentException> { scheduler(CountingJob(), CountingJob()) }
                }
            }
        }

        context("tenants") {
            test("a job visits every schema in use, as the tenant") {
                Schedule().run {
                    val visited = listOf("ACTIVE", "SUSPENDED", "OFFBOARDING").map { tenant(it) }
                    val seen = CopyOnWriteArrayList<Pair<TenantContext, String>>()
                    tenantWithoutSchema("REQUESTED")
                    tenantWithoutSchema("APPROVED")
                    tenantWithoutSchema("PROVISIONING")
                    tenantWithoutSchema("DELETED")

                    runner.forEachTenant("job", lease()) { dsl ->
                        seen += TenantContext.current() to dsl.fetchOne("SELECT current_user")!!.get(0).toString()
                    }

                    seen shouldContainExactlyInAnyOrder visited.map { TenantContext(it, "kek-$it") to TenantSchema(it).name }
                    TenantContext.currentOrNull().shouldBeNull()
                }
            }

            test("a failure rolls back its tenant and the job goes on") {
                Schedule().run {
                    val tenants = List(3) { tenant() }.sorted()

                    runner.forEachTenant("job", lease()) { dsl ->
                        dsl.execute("INSERT INTO fixture VALUES (1)")
                        check(TenantContext.current().tenantId != tenants.first()) { "stop" }
                    }

                    tenants.map { fixtureRows(it) } shouldBe listOf("0", "1", "1")
                }
            }

            test("a schema that is already gone does not stop the job") {
                Schedule().run {
                    val gone = tenantWithoutSchema("OFFBOARDING")
                    val tenants = listOf(tenant(), tenant())
                    val seen = CopyOnWriteArrayList<UUID>()

                    runner.forEachTenant("job", lease()) { seen += TenantContext.current().tenantId }

                    seen shouldContainExactlyInAnyOrder tenants
                    seen.contains(gone) shouldBe false
                }
            }

            test("the job renews its lease before each tenant") {
                Schedule().run {
                    repeat(3) { tenant() }
                    val free = CopyOnWriteArrayList<Boolean>()

                    runner.forEachTenant("job", lease()) {
                        moveClock(LEASE.minus(SECOND))
                        free += onAnotherWorker { locks.acquire("job") } != null
                    }

                    free shouldContainExactly listOf(false, false, false)
                }
            }

            test("a job that lost its lease stops before the next tenant") {
                Schedule().run {
                    repeat(3) { tenant() }
                    val seen = CopyOnWriteArrayList<UUID>()

                    runner.forEachTenant("job", lease()) {
                        seen += TenantContext.current().tenantId
                        moveClock(LEASE.plus(SECOND))
                        onAnotherWorker { lease() }
                    }

                    seen.size shouldBe 1
                }
            }
        }

        context("TTL cleanup") {
            test("a cleanup runs in every tenant and gets the time of the job") {
                Schedule().run {
                    val tenants = listOf(tenant(), tenant())
                    val times = CopyOnWriteArrayList<OffsetDateTime>()
                    val cleanup =
                        FixtureCleanup { dsl, now ->
                            dsl.execute("INSERT INTO fixture VALUES (1)")
                            times += now
                        }
                    moveClock(Duration.ofDays(30))

                    scheduler(cleanupJob(cleanup)).tick()

                    tenants.map { fixtureRows(it) } shouldBe listOf("1", "1")
                    times.toSet().size shouldBe 1
                    times.first().toInstant().isAfter(OffsetDateTime.now().plusDays(29).toInstant()) shouldBe true
                }
            }

            test("a failing cleanup does not hold back the cleanup of another module") {
                Schedule().run {
                    val tenant = tenant()
                    val failing = FixtureCleanup("failing") { _, _ -> throw IllegalArgumentException("stop") }
                    val working = FixtureCleanup("working") { dsl, _ -> dsl.execute("INSERT INTO fixture VALUES (1)") }

                    scheduler(cleanupJob(failing), cleanupJob(working)).tick()

                    fixtureRows(tenant) shouldBe "1"
                }
            }

            test("each cleanup has a lock of its own") {
                Schedule().run {
                    val first = cleanupJob(FixtureCleanup("first") { _, _ -> })
                    val second = cleanupJob(FixtureCleanup("second") { _, _ -> })

                    scheduler(first, second).tick()

                    owner.query("SELECT count(*) FROM platform.scheduled_locks") shouldBe "2"
                }
            }
        }
    })
