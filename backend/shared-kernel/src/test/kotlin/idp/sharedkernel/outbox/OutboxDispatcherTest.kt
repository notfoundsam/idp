package idp.sharedkernel.outbox

import idp.db.jooq.platform.tables.Outbox.OUTBOX
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.migration.query
import idp.sharedkernel.migration.update
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.exception.DataAccessException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.concurrent.thread

private val LEASE: Duration = Duration.ofMinutes(5)
private val RETRY_DELAY: Duration = Duration.ofSeconds(30)
private const val MAX_ATTEMPTS = 3
private const val WAIT_SECONDS = 60L

private class MovableClock : Clock() {
    var offset: Duration = Duration.ZERO

    override fun instant(): Instant = Instant.now().plus(offset)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

private class Recording(
    override val eventType: String = "created",
    override val scope: EventScope = EventScope.TENANT,
    override val name: String = "recording",
    private val action: (OutboxEvent) -> Unit = {},
) : EventHandler {
    val seen = CopyOnWriteArrayList<OutboxEvent>()

    override fun handle(event: OutboxEvent) {
        seen += event
        action(event)
    }
}

private data class SignedUpV1(
    val userId: UUID,
)

private data class SignedUpV2(
    val userId: UUID,
    val invitedBy: UUID? = null,
)

private fun event(
    type: String = "created",
    version: Long = 1,
    payload: Any = emptyMap<String, Any>(),
) = NewEvent("user", UUID.randomUUID(), version, type, payload)

private class Outbox {
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
    val worker = database.role("worker_runtime")
    val a: UUID = UUID.randomUUID()
    val b: UUID = UUID.randomUUID()
    val clock = MovableClock()
    val publisher = OutboxPublisher()
    val tenants = TenantTransaction(worker)
    val platform = PlatformTransaction(worker)

    init {
        listOf(a, b).forEach { id ->
            owner.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES ('$id', 'tenant', 'ACTIVE', 'kek-$id')")
            TenantSchemaFactory(owner).create(id)
            TenantMigrationRunner(owner).migrateTenant(id)
            owner.update("""CREATE TABLE "${TenantSchema(id).name}".fixture (id int)""")
        }
    }

    fun dispatcher(
        vararg handlers: EventHandler,
        batchSize: Int = 100,
        source: DataSource = worker,
    ) = OutboxDispatcher(
        PlatformTransaction(source),
        IdempotentHandler(TenantTransaction(source)),
        handlers.toList(),
        clock,
        batchSize,
        LEASE,
        RETRY_DELAY,
        MAX_ATTEMPTS,
    )

    fun <T> inTenant(
        id: UUID,
        block: () -> T,
    ): T = TenantContext.with(TenantContext(id, kekUri = null), block)

    fun publish(
        tenant: UUID,
        event: NewEvent = event(),
    ) {
        inTenant(tenant) { tenants.execute { publisher.publish(it, event) } }
    }

    fun publishPlatform(event: NewEvent = event()) {
        platform.execute { publisher.publish(it, event) }
    }

    fun status(): String = owner.query("SELECT status FROM platform.outbox")

    fun attempts(): String = owner.query("SELECT attempts FROM platform.outbox")

    fun processed(tenant: UUID): String = owner.query("""SELECT count(*) FROM "${TenantSchema(tenant).name}".processed_events""")

    fun fixtureRows(tenant: UUID): String = owner.query("""SELECT count(*) FROM "${TenantSchema(tenant).name}".fixture""")

    fun redeliver() {
        owner.update("UPDATE platform.outbox SET status = 'PENDING', attempts = 0, available_at = now()")
    }

    fun afterRetryDelay() {
        clock.offset += RETRY_DELAY.multipliedBy(1L shl MAX_ATTEMPTS)
    }
}

class OutboxDispatcherTest :
    FunSpec({
        context("publishing") {
            test("a rolled back transaction publishes nothing") {
                Outbox().run {
                    shouldThrow<IllegalArgumentException> {
                        inTenant(a) {
                            tenants.execute {
                                publisher.publish(it, event())
                                throw IllegalArgumentException("stop")
                            }
                        }
                    }

                    owner.query("SELECT count(*) FROM platform.outbox") shouldBe "0"
                }
            }

            test("an event cannot be published outside a transaction") {
                Outbox().run {
                    val dsl = platform.execute { it }

                    shouldThrow<IllegalStateException> { publisher.publish(dsl, event()) }

                    owner.query("SELECT count(*) FROM platform.outbox") shouldBe "0"
                }
            }

            test("the event carries the tenant and the aggregate version") {
                Outbox().run {
                    val handler = Recording()
                    val published = event(version = 7)
                    publish(a, published)

                    dispatcher(handler).dispatch()

                    handler.seen.single().run {
                        tenantId shouldBe a
                        aggregateId shouldBe published.aggregateId
                        aggregateVersion shouldBe 7
                        causationId.shouldBeNull()
                    }
                }
            }

            test("a tenant role cannot publish for another tenant or for nobody") {
                Outbox().run {
                    fun insert(tenant: String) =
                        """
                        INSERT INTO platform.outbox (id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, correlation_id)
                        VALUES (gen_random_uuid(), $tenant, 'user', gen_random_uuid(), 1, 'created', gen_random_uuid())
                        """

                    listOf("'$b'", "NULL").forEach { tenant ->
                        shouldThrow<DataAccessException> {
                            inTenant(a) { tenants.execute { it.execute(insert(tenant)) } }
                        }.message shouldContain "row-level security"
                    }
                    inTenant(a) { tenants.execute { it.execute(insert("'$a'")) } }

                    owner.query("SELECT tenant_id FROM platform.outbox") shouldBe a.toString()
                }
            }

            test("ops_runtime publishes but does not read") {
                Outbox().run {
                    val ops = PlatformTransaction(database.role("ops_runtime"))

                    ops.execute { publisher.publish(it, event()) }
                    inTenant(a) { ops.execute { publisher.publish(it, event()) } }

                    owner.query("SELECT count(*) FROM platform.outbox") shouldBe "2"
                    shouldThrow<DataAccessException> {
                        ops.execute { it.fetchCount(OUTBOX) }
                    }.message shouldContain "permission denied"
                }
            }

            test("worker_runtime reads the events of every tenant") {
                Outbox().run {
                    publish(a)
                    publish(b)
                    publishPlatform()

                    platform.execute { it.fetchCount(OUTBOX) } shouldBe 3
                }
            }
        }

        context("claiming") {
            test("two workers on PgBouncer never run the same event") {
                Outbox().run {
                    repeat(40) { publish(if (it % 2 == 0) a else b) }
                    val handler = Recording(scope = EventScope.PLATFORM)
                    val pooled = database.pooled("worker_runtime")
                    val start = CyclicBarrier(2)

                    val workers =
                        List(2) {
                            val dispatcher = dispatcher(handler, batchSize = 5, source = pooled)
                            thread(isDaemon = true) {
                                start.await(WAIT_SECONDS, TimeUnit.SECONDS)
                                while (dispatcher.dispatch() > 0) Unit
                            }
                        }
                    workers.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }

                    workers.map { it.isAlive } shouldBe listOf(false, false)
                    handler.seen shouldHaveSize 40
                    handler.seen.map { it.id }.toSet() shouldHaveSize 40
                    owner.query("SELECT count(*) FROM platform.outbox WHERE status = 'DONE'") shouldBe "40"
                }
            }

            test("a worker skips an event another one holds") {
                Outbox().run {
                    publish(a)
                    publish(b)
                    val handler = Recording(scope = EventScope.PLATFORM)
                    val held =
                        owner.connection.use { connection ->
                            connection.autoCommit = false
                            val id =
                                connection.createStatement().use { statement ->
                                    statement
                                        .executeQuery("SELECT id FROM platform.outbox WHERE tenant_id = '$a' FOR UPDATE")
                                        .use { rows ->
                                            rows.next()
                                            rows.getString(1)
                                        }
                                }

                            dispatcher(handler).dispatch() shouldBe 1
                            connection.rollback()
                            id
                        }

                    handler.seen.single().tenantId shouldBe b
                    owner.query("SELECT status FROM platform.outbox WHERE id = '$held'") shouldBe "PENDING"
                }
            }

            test("a busy tenant does not hold back the others") {
                Outbox().run {
                    repeat(20) { publish(a) }
                    publish(b)
                    publishPlatform()
                    val handler = Recording(scope = EventScope.PLATFORM)

                    dispatcher(handler, batchSize = 3).dispatch()

                    handler.seen.map { it.tenantId } shouldContainExactlyInAnyOrder listOf(a, b, null)
                }
            }

            test("a claimed event comes back after the lease and the late worker does not overwrite the new claim") {
                Outbox().run {
                    publish(a)
                    val second = Recording(scope = EventScope.PLATFORM)
                    val first =
                        Recording(scope = EventScope.PLATFORM) {
                            dispatcher(second).dispatch() shouldBe 0
                            clock.offset = LEASE.plusSeconds(1)
                            dispatcher(second).dispatch() shouldBe 1
                            throw IllegalArgumentException("the first worker fails late")
                        }

                    dispatcher(first).dispatch()

                    second.seen shouldHaveSize 1
                    status() shouldBe "DONE"
                    attempts() shouldBe "2"
                }
            }
        }

        context("idempotency") {
            test("a repeated delivery does not run the handler twice") {
                Outbox().run {
                    publish(a)
                    val handler = Recording()

                    dispatcher(handler).dispatch()
                    redeliver()
                    dispatcher(handler).dispatch()

                    handler.seen shouldHaveSize 1
                    processed(a) shouldBe "1"
                    processed(b) shouldBe "0"
                    status() shouldBe "DONE"
                }
            }

            test("processed_events commits together with the handler") {
                Outbox().run {
                    publish(a)
                    var fail = true
                    val handler =
                        Recording {
                            tenants.execute { it.execute("INSERT INTO fixture VALUES (1)") }
                            if (fail) throw IllegalArgumentException("stop")
                        }

                    dispatcher(handler).dispatch()

                    processed(a) shouldBe "0"
                    fixtureRows(a) shouldBe "0"

                    fail = false
                    afterRetryDelay()
                    dispatcher(handler).dispatch()

                    processed(a) shouldBe "1"
                    fixtureRows(a) shouldBe "1"
                }
            }

            test("a retry skips the handlers that already finished") {
                Outbox().run {
                    publish(a)
                    var fail = true
                    val finished = Recording(name = "finished")
                    val failing = Recording(name = "failing") { if (fail) throw IllegalArgumentException("stop") }
                    val dispatcher = dispatcher(finished, failing)

                    dispatcher.dispatch()
                    fail = false
                    afterRetryDelay()
                    dispatcher.dispatch()

                    finished.seen shouldHaveSize 1
                    failing.seen shouldHaveSize 2
                    status() shouldBe "DONE"
                }
            }

            test("two handlers with one name are rejected") {
                Outbox().run {
                    shouldThrow<IllegalArgumentException> { dispatcher(Recording(), Recording()) }
                }
            }
        }

        context("retries") {
            test("a failing event waits for its delay and goes dead at the limit") {
                Outbox().run {
                    publish(a)
                    val handler = Recording { throw IllegalArgumentException("stop") }
                    val dispatcher = dispatcher(handler)

                    dispatcher.dispatch() shouldBe 1
                    status() shouldBe "PENDING"
                    attempts() shouldBe "1"

                    dispatcher.dispatch() shouldBe 0
                    handler.seen shouldHaveSize 1

                    repeat(MAX_ATTEMPTS - 1) {
                        afterRetryDelay()
                        dispatcher.dispatch() shouldBe 1
                    }
                    status() shouldBe "DEAD"

                    afterRetryDelay()
                    dispatcher.dispatch() shouldBe 0
                    handler.seen shouldHaveSize MAX_ATTEMPTS
                }
            }

            test("an event that took every attempt down with its worker goes dead without running") {
                Outbox().run {
                    publish(a)
                    owner.update("UPDATE platform.outbox SET attempts = $MAX_ATTEMPTS")
                    val handler = Recording()

                    dispatcher(handler).dispatch() shouldBe 0

                    handler.seen shouldHaveSize 0
                    status() shouldBe "DEAD"
                }
            }

            test("an event nobody handles is finished") {
                Outbox().run {
                    publish(a, event("ignored"))

                    dispatcher(Recording()).dispatch()

                    status() shouldBe "DONE"
                }
            }
        }

        context("scope of a handler") {
            test("the worker restores the tenant from the event") {
                Outbox().run {
                    publish(a)
                    publish(b)
                    val seen = CopyOnWriteArrayList<Pair<TenantContext, String>>()
                    val handler =
                        Recording {
                            val role = tenants.execute { dsl -> dsl.fetchOne("SELECT current_user")!!.get(0).toString() }
                            seen += TenantContext.current() to role
                        }

                    dispatcher(handler).dispatch()

                    seen shouldContainExactlyInAnyOrder
                        listOf(
                            TenantContext(a, "kek-$a") to TenantSchema(a).name,
                            TenantContext(b, "kek-$b") to TenantSchema(b).name,
                        )
                    TenantContext.currentOrNull().shouldBeNull()
                }
            }

            test("a tenant handler fails on an event without a tenant") {
                Outbox().run {
                    publishPlatform()
                    val handler = Recording()

                    dispatcher(handler).dispatch()

                    handler.seen shouldHaveSize 0
                    status() shouldBe "PENDING"
                    attempts() shouldBe "1"
                }
            }

            test("a platform handler runs for a tenant without a schema, outside its context") {
                Outbox().run {
                    val provisioning = UUID.randomUUID()
                    owner.update("INSERT INTO platform.tenants (id, name, status) VALUES ('$provisioning', 'new', 'PROVISIONING')")
                    inTenant(provisioning) { platform.execute { publisher.publish(it, event()) } }
                    val contexts = CopyOnWriteArrayList<TenantContext?>()
                    val handler = Recording(scope = EventScope.PLATFORM) { contexts += TenantContext.currentOrNull() }

                    dispatcher(handler).dispatch()

                    handler.seen.single().tenantId shouldBe provisioning
                    contexts.single().shouldBeNull()
                    status() shouldBe "DONE"
                }
            }
        }

        context("event chain") {
            test("a reaction inherits the correlation and names its cause") {
                Outbox().run {
                    val reacted = Recording(eventType = "reacted", name = "reacted")
                    val publishing =
                        Recording(name = "publishing") {
                            tenants.execute { dsl -> publisher.publish(dsl, event("reacted")) }
                        }
                    publish(a)

                    dispatcher(publishing, reacted).dispatch()
                    dispatcher(publishing, reacted).dispatch()

                    val cause = publishing.seen.single()
                    reacted.seen.single().run {
                        correlationId shouldBe cause.correlationId
                        causationId shouldBe cause.id
                    }
                }
            }

            test("the chain ends with the handler") {
                Outbox().run {
                    publish(a)
                    dispatcher(Recording()).dispatch()
                    publish(b)
                    val handler = Recording()

                    dispatcher(handler).dispatch()

                    handler.seen
                        .single()
                        .causationId
                        .shouldBeNull()
                }
            }

            test("separate root events get separate correlations") {
                Outbox().run {
                    publish(a)
                    publish(a)
                    val handler = Recording()

                    dispatcher(handler).dispatch()

                    handler.seen.map { it.correlationId }.toSet() shouldHaveSize 2
                }
            }
        }

        context("payload") {
            test("a handler ignores fields it does not know") {
                Outbox().run {
                    val user = UUID.randomUUID()
                    publish(a, event(payload = SignedUpV2(user, invitedBy = UUID.randomUUID())))
                    val handler = Recording()

                    dispatcher(handler).dispatch()

                    handler.seen.single().payload<SignedUpV1>() shouldBe SignedUpV1(user)
                }
            }

            test("an old event without a new field is still handled") {
                Outbox().run {
                    val user = UUID.randomUUID()
                    publish(a, event(payload = SignedUpV1(user)))
                    val handler = Recording()

                    dispatcher(handler).dispatch()

                    handler.seen.single().payload<SignedUpV2>() shouldBe SignedUpV2(user)
                }
            }
        }
    })
