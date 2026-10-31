package idp.sharedkernel.bus

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.bus.decorators.LoggerDecorator
import idp.sharedkernel.bus.decorators.SecurityDecorator
import idp.sharedkernel.bus.decorators.ThrottleDecorator
import idp.sharedkernel.bus.decorators.TransactionDecorator
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.NewEvent
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.security.AccessDeniedException
import idp.sharedkernel.security.Permission
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.throttle.RateDecision
import idp.sharedkernel.throttle.RateLimiter
import idp.sharedkernel.throttle.ThrottleKeys
import idp.sharedkernel.throttle.ThrottleLimit
import idp.sharedkernel.web.ClientIp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource
import kotlin.reflect.KClass

private const val SECRET = "jane.doe@example.com"
private const val CLIENT_ADDRESS = "203.0.113.7"
private val MEMBER = Role("member")
private val ADMIN = Role("admin")
private val READ = Permission("read")
private val WRITE = Permission("write")

private data class TenantAction(
    val email: String = SECRET,
) : Command<String>

private data class PlatformAction(
    val email: String = SECRET,
) : Command<String> {
    override val transaction = TransactionScope.PLATFORM
}

private class Hashing : Command<String> {
    override val transaction = TransactionScope.NONE
}

private class PublicLookup : Query<String> {
    override val transaction = TransactionScope.NONE
}

private class AdminLookup : Query<String> {
    override val transaction = TransactionScope.NONE
}

private class Unlisted : Command<String> {
    override val transaction = TransactionScope.NONE
}

private class Failing : Command<String> {
    override val transaction = TransactionScope.NONE
}

private class Handler<M : Message<String>>(
    override val messageType: KClass<M>,
    private val action: (M) -> String = { "done" },
) : MessageHandler<M, String> {
    val handled = CopyOnWriteArrayList<M>()

    override fun handle(message: M): String {
        handled += message
        return action(message)
    }
}

private inline fun <reified M : Message<String>> handler(noinline action: (M) -> String = { "done" }) = Handler(M::class, action)

private class RecordingAudit : AuditLog {
    val denials = CopyOnWriteArrayList<AuditRecord>()
    val insideTransaction = CopyOnWriteArrayList<Boolean>()

    override fun recordChange(record: AuditRecord) = error("A denial never joins a transaction")

    override fun recordDenial(record: AuditRecord) {
        denials += record
        insideTransaction += TransactionSynchronizationManager.isActualTransactionActive()
    }
}

private class Config(
    override val permissions: Map<KClass<out Message<*>>, Permission?>,
    private val grants: Map<Role, Set<Permission>>,
    private val onRead: () -> Unit = {},
) : SecurityConfig {
    override val roles: Map<Role, Set<Permission>>
        get() = grants.also { onRead() }
}

private class Bus {
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
    val sql = JdbcTemplate(owner)
    val tenant = TenantContext(UUID.randomUUID(), null)
    val events = CopyOnWriteArrayList<String>()
    val runtime: DataSource =
        object : DelegatingDataSource(database.role("app_runtime")) {
            override fun getConnection(): Connection {
                events += "transaction"
                return super.getConnection()
            }
        }
    val tenantTransaction = TenantTransaction(runtime)
    val platformTransaction = PlatformTransaction(runtime)
    val audit = RecordingAudit()

    init {
        sql.update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'ACTIVE')", tenant.tenantId)
        TenantSchemaFactory(owner).create(tenant.tenantId)
    }

    private val allPermissions: Map<KClass<out Message<*>>, Permission?> =
        mapOf(
            TenantAction::class to WRITE,
            PlatformAction::class to WRITE,
            Hashing::class to null,
            PublicLookup::class to null,
            AdminLookup::class to READ,
            Failing::class to null,
        )

    fun config(handlers: List<MessageHandler<*, *>>? = null): Config =
        Config(
            allPermissions.filterKeys { message -> handlers == null || handlers.any { it.messageType == message } },
            mapOf(MEMBER to setOf(WRITE), ADMIN to setOf(READ, WRITE)),
        )

    val defaultHandlers: List<MessageHandler<*, *>> =
        listOf(
            handler<TenantAction>(),
            handler<PlatformAction>(),
            handler<Hashing>(),
            handler<PublicLookup>(),
            handler<AdminLookup>(),
            handler<Unlisted>(),
            handler<Failing> { error("Nobody may read $SECRET") },
        )

    fun pipeline(
        handlers: List<MessageHandler<*, *>> = defaultHandlers,
        configs: List<SecurityConfig> = listOf(config(handlers)),
        logger: LoggerDecorator = LoggerDecorator(),
        throttle: ThrottleDecorator? = null,
    ) = BusPipeline(
        handlers = handlers,
        security = SecurityDecorator(configs, audit),
        logger = logger,
        transaction = TransactionDecorator(tenantTransaction, platformTransaction),
        throttle = throttle,
    )

    fun bus(handlers: List<MessageHandler<*, *>> = defaultHandlers) = CommandBus(pipeline(handlers))

    fun queries(configs: List<SecurityConfig> = listOf(config())) = QueryBus(pipeline(configs = configs))

    fun <T> asRole(
        role: Role,
        block: () -> T,
    ): T = SecurityContext.with(Principal(Actor.User(USER), role)) { TenantContext.with(tenant, block) }

    fun <T> anonymous(block: () -> T): T = TenantContext.with(tenant, block)
}

private val USER: UUID = UUID.randomUUID()

private class CapturedLog : AutoCloseable {
    private val logger = LoggerFactory.getLogger(LoggerDecorator::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>().also { it.start() }

    init {
        logger.addAppender(appender)
    }

    val lines: List<ILoggingEvent> get() = appender.list

    override fun close() {
        logger.detachAppender(appender)
    }
}

class BusDecoratorsTest :
    FunSpec({
        val bus = Bus()

        beforeTest {
            bus.events.clear()
            bus.audit.denials.clear()
            bus.audit.insideTransaction.clear()
        }

        context("order") {
            test("a message goes through Throttle, Security, Logger, Transaction and the handler") {
                val limiter =
                    object : RateLimiter {
                        override fun acquire(
                            key: String,
                            limit: ThrottleLimit,
                        ): RateDecision {
                            bus.events += "throttle"
                            return RateDecision.Allowed
                        }
                    }
                val security = Config(mapOf(PlatformAction::class to WRITE), mapOf(MEMBER to setOf(WRITE))) { bus.events += "security" }
                val pipeline =
                    bus.pipeline(
                        handlers = listOf(handler<PlatformAction> { "done".also { bus.events += "handler" } }),
                        configs = listOf(security),
                        logger =
                            LoggerDecorator {
                                bus.events += "logger"
                                System.nanoTime()
                            },
                        throttle =
                            ThrottleDecorator(
                                emptyList(),
                                ThrottleLimit(1, Duration.ofMinutes(1)),
                                limiter,
                                ThrottleKeys(ByteArray(32)),
                            ),
                    )

                ClientIp.with(ClientIp(CLIENT_ADDRESS)) {
                    bus.asRole(MEMBER) { CommandBus(pipeline).dispatch(PlatformAction()) }
                }

                bus.events.shouldContainExactly("throttle", "security", "logger", "transaction", "handler", "logger")
            }
        }

        context("security") {
            test("a message without an entry in SecurityConfig is refused, whatever the role") {
                val failure = shouldThrow<AccessDeniedException> { bus.asRole(ADMIN) { bus.bus().dispatch(Unlisted()) } }

                failure.authenticated shouldBe true
                bus.audit.denials shouldHaveSize 1
            }

            test("null makes a message public for a caller without a principal") {
                bus.anonymous { bus.queries().ask(PublicLookup()) } shouldBe "done"

                bus.audit.denials.shouldBeEmpty()
            }

            test("a message with a permission is refused for a caller without a principal") {
                val failure = shouldThrow<AccessDeniedException> { bus.anonymous { bus.queries().ask(AdminLookup()) } }

                failure.authenticated shouldBe false
                bus.audit.denials
                    .map { it.actor }
                    .shouldContainExactly(Actor.Anonymous)
            }

            test("a message names a permission, and the role reaches it only through the config") {
                bus.asRole(ADMIN) { bus.queries().ask(AdminLookup()) } shouldBe "done"

                shouldThrow<AccessDeniedException> { bus.asRole(MEMBER) { bus.queries().ask(AdminLookup()) } }

                val regranted = Config(mapOf(AdminLookup::class to READ), mapOf(MEMBER to setOf(READ)))
                bus.asRole(MEMBER) { bus.queries(listOf(regranted)).ask(AdminLookup()) } shouldBe "done"
            }

            test("a role with no entry holds no permissions") {
                shouldThrow<AccessDeniedException> { bus.asRole(Role("stranger")) { bus.bus().dispatch(PlatformAction()) } }
            }

            test("each audience maps roles for its own messages only") {
                val account = Config(mapOf(PublicLookup::class to READ), mapOf(MEMBER to setOf(READ)))
                val admin = Config(mapOf(AdminLookup::class to READ), mapOf(ADMIN to setOf(READ)))

                bus.asRole(MEMBER) { bus.queries(listOf(account, admin)).ask(PublicLookup()) } shouldBe "done"
                shouldThrow<AccessDeniedException> { bus.asRole(MEMBER) { bus.queries(listOf(account, admin)).ask(AdminLookup()) } }
            }

            test("two configs cannot claim one message") {
                val first = Config(mapOf(AdminLookup::class to READ), emptyMap())
                val second = Config(mapOf(AdminLookup::class to null), emptyMap())

                shouldThrow<IllegalArgumentException> { SecurityDecorator(listOf(first, second), bus.audit) }
            }

            test("a denial is audited outside any transaction and the handler stays out") {
                val handled = handler<PlatformAction>()
                val commands = bus.bus(listOf(handled))

                shouldThrow<AccessDeniedException> {
                    ClientIp.with(ClientIp(CLIENT_ADDRESS)) { bus.asRole(Role("stranger")) { commands.dispatch(PlatformAction()) } }
                }

                handled.handled.shouldBeEmpty()
                bus.events.shouldBeEmpty()
                bus.audit.insideTransaction.shouldContainExactly(false)
                val denial = bus.audit.denials.single()
                denial.actor shouldBe Actor.User(USER)
                denial.action shouldBe "access.denied"
                denial.ip shouldBe CLIENT_ADDRESS
                denial.details shouldBe mapOf("message" to PlatformAction::class.java.name)
            }
        }

        context("transaction") {
            test("a tenant message runs in the tenant transaction with its role") {
                val role = TenantSchema(bus.tenant.tenantId).name
                val handled =
                    handler<TenantAction> {
                        bus.tenantTransaction.join { dsl -> dsl.fetchOne("SELECT current_user")!!.get(0).toString() }
                    }

                bus.asRole(MEMBER) { bus.bus(listOf(handled)).dispatch(TenantAction()) } shouldBe role
            }

            test("a platform message runs in the platform transaction without a tenant") {
                val handled =
                    handler<PlatformAction> {
                        bus.platformTransaction.join { dsl -> dsl.fetchOne("SELECT current_user")!!.get(0).toString() }
                    }
                val principal = Principal(Actor.Operator("ops"), MEMBER)

                SecurityContext.with(principal) {
                    bus.bus(listOf(handled)).dispatch(PlatformAction())
                } shouldBe "app_runtime"
            }

            test("a noTransaction message runs without a connection, because Argon2id must not hold one") {
                val active =
                    handler<Hashing> {
                        TransactionSynchronizationManager.isActualTransactionActive().toString()
                    }

                bus.anonymous { bus.bus(listOf(active)).dispatch(Hashing()) } shouldBe "false"

                bus.events.shouldBeEmpty()
            }

            test("a failed handler rolls its transaction back") {
                val failing =
                    handler<TenantAction> {
                        bus.tenantTransaction.join { dsl -> OutboxPublisher().publish(dsl, event()) }
                        error("rolled back")
                    }

                shouldThrow<IllegalStateException> {
                    bus.asRole(MEMBER) { bus.bus(listOf(failing)).dispatch(TenantAction()) }
                }

                bus.sql.queryForObject("SELECT count(*) FROM platform.outbox", Int::class.java) shouldBe 0
            }
        }

        context("handlers") {
            test("two handlers for one message stop the bus from starting") {
                shouldThrow<IllegalArgumentException> {
                    bus.pipeline(
                        handlers = listOf(handler<PlatformAction>(), handler<PlatformAction>()),
                    )
                }.message shouldContain "PlatformAction"
            }

            test("a configured message without a handler stops the bus from starting") {
                val failure =
                    shouldThrow<IllegalArgumentException> {
                        bus.pipeline(handlers = listOf(handler<PlatformAction>()), configs = listOf(bus.config()))
                    }

                failure.message shouldContain "AdminLookup"
            }

            test("the query bus carries the handler's result") {
                val echo = handler<PublicLookup> { "answer" }

                bus.anonymous { QueryBus(bus.pipeline(handlers = listOf(echo))).ask(PublicLookup()) } shouldBe "answer"
            }
        }

        context("logging") {
            test("a line holds the type, the outcome, the duration and the correlation, and nothing of the message") {
                CapturedLog().use { log ->
                    bus.asRole(MEMBER) { bus.bus().dispatch(TenantAction()) }

                    val line = log.lines.single().formattedMessage
                    line shouldMatch Regex("message=\\S+TenantAction outcome=OK durationMs=\\d+ correlationId=[0-9a-f-]{36}")
                    line shouldNotContain SECRET
                }
            }

            test("a failure is logged by exception type, because exception messages carry data") {
                CapturedLog().use { log ->
                    shouldThrow<IllegalStateException> { bus.anonymous { bus.bus().dispatch(Failing()) } }

                    val event = log.lines.single()
                    event.formattedMessage shouldContain "outcome=IllegalStateException"
                    event.formattedMessage shouldNotContain SECRET
                    event.throwableProxy.shouldBeNull()
                }
            }

            test("an event from the handler carries the correlation of the log line") {
                val publishing =
                    handler<TenantAction> {
                        bus.tenantTransaction.join { dsl -> OutboxPublisher().publish(dsl, event()) }
                        "done"
                    }

                CapturedLog().use { log ->
                    bus.asRole(MEMBER) { bus.bus(listOf(publishing)).dispatch(TenantAction()) }

                    val stored = bus.sql.queryForObject("SELECT correlation_id FROM platform.outbox", UUID::class.java)
                    log.lines.single().formattedMessage shouldContain "correlationId=$stored"
                }
            }
        }
    })

private fun event() = NewEvent("user", UUID.randomUUID(), 1, "user.created")
