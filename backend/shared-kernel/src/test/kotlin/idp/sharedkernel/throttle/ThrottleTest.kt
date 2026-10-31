package idp.sharedkernel.throttle

import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.bus.BusPipeline
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.Message
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.sharedkernel.bus.decorators.LoggerDecorator
import idp.sharedkernel.bus.decorators.SecurityDecorator
import idp.sharedkernel.bus.decorators.ThrottleDecorator
import idp.sharedkernel.bus.decorators.TransactionDecorator
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.security.Permission
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantContextRequired
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.MovableClock
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestValkey
import idp.sharedkernel.throttle.adapter.ValkeyRateLimiter
import idp.sharedkernel.web.ClientIp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.jdbc.datasource.DelegatingDataSource
import java.net.ServerSocket
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.toJavaDuration

private const val ADDRESS = "203.0.113.7"
private const val OTHER_ADDRESS = "198.51.100.9"
private const val MESSAGE_FAILURES = "idp.throttle.failures"
private val SECRET = ByteArray(32) { 1 }
private val MINUTE: Duration = Duration.ofMinutes(1)
private val SHORT: Duration = Duration.ofMillis(200)
private val CONNECT: Duration = Duration.ofSeconds(1)
private val LONG: Duration = Duration.ofSeconds(5)
private val COOLDOWN: Duration = Duration.ofSeconds(30)
private val TENANT = TenantContext(UUID.randomUUID(), null)
private val OTHER_TENANT = TenantContext(UUID.randomUUID(), null)
private val USER: UUID = UUID.randomUUID()
private val MEMBER = Role("member")
private val PRINCIPAL = Principal(Actor.User(USER), MEMBER)

private class Action : Command<Unit> {
    override val transaction = TransactionScope.PLATFORM
}

private class Limited : Command<Unit>

private class Lookup : Command<Unit>

private class SpyLimiter(
    private val decision: RateDecision = RateDecision.Allowed,
) : RateLimiter {
    val calls = mutableListOf<Pair<String, ThrottleLimit>>()

    override fun acquire(
        key: String,
        limit: ThrottleLimit,
    ): RateDecision {
        calls += key to limit
        return decision
    }
}

private fun config(vararg rules: Pair<KClass<out Message<*>>, List<ThrottleRule>>) =
    object : ThrottleConfig {
        override val rules = mapOf(*rules)
    }

private fun decorator(
    limiter: RateLimiter,
    configs: List<ThrottleConfig> = emptyList(),
    default: ThrottleLimit = ThrottleLimit(10, MINUTE),
    secret: ByteArray = SECRET,
) = ThrottleDecorator(configs, default, limiter, ThrottleKeys(secret))

private fun <T> request(
    principal: Principal? = null,
    tenant: TenantContext = TENANT,
    address: String = ADDRESS,
    block: () -> T,
): T =
    ClientIp.with(ClientIp(address)) {
        TenantContext.with(tenant) {
            if (principal == null) block() else SecurityContext.with(principal, block)
        }
    }

private fun ThrottleDecorator.pass(message: Message<Unit> = Action()) = around(message) {}

private object NoDenials : AuditLog {
    override fun recordChange(record: AuditRecord) = error("Not expected")

    override fun recordDenial(record: AuditRecord) = error("Throttled messages never reach Security")
}

private fun ValkeyRateLimiter.decide(
    key: String,
    capacity: Long = 1,
) = acquire(key, ThrottleLimit(capacity, MINUTE))

private fun limiter(
    url: String,
    registry: SimpleMeterRegistry = SimpleMeterRegistry(),
    clock: MovableClock = MovableClock(),
    timeout: Duration = LONG,
    connectTimeout: Duration = LONG,
) = ValkeyRateLimiter(url, timeout, connectTimeout, COOLDOWN, registry, clock)

private fun uniqueKey() = "throttle:test:${UUID.randomUUID()}"

class ThrottleTest :
    FunSpec({
        context("keys") {
            test("a signed-in caller is limited by user, an anonymous one by IP") {
                val limiter = SpyLimiter()
                val decorator = decorator(limiter)

                request(PRINCIPAL) { decorator.pass() }
                request { decorator.pass() }

                val (byUser, byIp) = limiter.calls.map { it.first }
                byUser shouldContain ":USER:"
                byUser shouldContain USER.toString()
                byIp shouldContain ":IP:"
            }

            test("the IP dimension applies to a signed-in caller when the rule says so") {
                val limiter = SpyLimiter()
                val rules = config(Action::class to listOf(ThrottleRule(ThrottleLimit(5, MINUTE), ThrottleDimension.IP)))

                request(PRINCIPAL) { decorator(limiter, listOf(rules)).pass() }

                limiter.calls.single().first shouldContain ":IP:"
            }

            test("the USER dimension falls back to IP for a caller without a principal") {
                val limiter = SpyLimiter()
                val rules = config(Action::class to listOf(ThrottleRule(ThrottleLimit(5, MINUTE), ThrottleDimension.USER)))

                request { decorator(limiter, listOf(rules)).pass() }

                limiter.calls.single().first shouldContain ":IP:"
            }

            test("the TENANT dimension is one bucket for everybody in the tenant") {
                val limiter = SpyLimiter()
                val rules = config(Action::class to listOf(ThrottleRule(ThrottleLimit(5, MINUTE), ThrottleDimension.TENANT)))
                val decorator = decorator(limiter, listOf(rules))

                request(PRINCIPAL) { decorator.pass() }
                request(address = OTHER_ADDRESS) { decorator.pass() }

                limiter.calls.map { it.first }.toSet() shouldHaveSize 1
                limiter.calls.first().first shouldContain ":TENANT:"
            }

            test("every key holds the tenant, so tenants share no bucket") {
                val limiter = SpyLimiter()
                val decorator = decorator(limiter)

                request { decorator.pass() }
                request(tenant = OTHER_TENANT) { decorator.pass() }

                val (first, second) = limiter.calls.map { it.first }
                first shouldContain TENANT.tenantId.toString()
                second shouldContain OTHER_TENANT.tenantId.toString()
                first shouldNotBe second
            }

            test("a key requires a tenant") {
                shouldThrow<TenantContextRequired> {
                    ClientIp.with(ClientIp(ADDRESS)) { decorator(SpyLimiter()).pass() }
                }
            }

            test("an IP is stored as a keyed HMAC") {
                fun keyWith(
                    secret: ByteArray,
                    address: String = ADDRESS,
                ): String {
                    val limiter = SpyLimiter()
                    request(address = address) { decorator(limiter, secret = secret).pass() }
                    return limiter.calls.single().first
                }

                val key = keyWith(SECRET)

                key shouldNotContain ADDRESS
                key shouldBe keyWith(SECRET)
                key shouldNotBe keyWith(SECRET, OTHER_ADDRESS)
                key shouldNotBe keyWith(ByteArray(32) { 2 })
            }

            test("each message has its own bucket") {
                val limiter = SpyLimiter()
                val decorator = decorator(limiter)

                request { decorator.pass(Action()) }
                request { decorator.pass(Lookup()) }

                limiter.calls.map { it.first }.toSet() shouldHaveSize 2
            }
        }

        context("limits") {
            test("a message without an entry in ThrottleConfig uses the default limit") {
                val limiter = SpyLimiter()
                val default = ThrottleLimit(7, MINUTE)
                val rules = config(Limited::class to listOf(ThrottleRule(ThrottleLimit(1, MINUTE))))

                request { decorator(limiter, listOf(rules), default).pass(Action()) }

                limiter.calls.single().second shouldBe default
            }

            test("a message with an entry uses its own limit") {
                val limiter = SpyLimiter()
                val limit = ThrottleLimit(1, MINUTE)
                val rules = config(Limited::class to listOf(ThrottleRule(limit)))

                request { decorator(limiter, listOf(rules)).pass(Limited()) }

                limiter.calls.single().second shouldBe limit
            }

            test("several rules all apply, and a refused one spares the rules after it") {
                val refusal = RateDecision.Rejected(Duration.ofSeconds(5))
                val first = ThrottleRule(ThrottleLimit(1, MINUTE), ThrottleDimension.IP)
                val second = ThrottleRule(ThrottleLimit(100, MINUTE), ThrottleDimension.TENANT)
                val rules = config(Action::class to listOf(first, second))

                val passing = SpyLimiter()
                request { decorator(passing, listOf(rules)).pass() }
                passing.calls.map { it.second }.shouldContainExactly(first.limit, second.limit)

                val refusing = SpyLimiter(refusal)
                shouldThrow<TooManyRequestsException> { request { decorator(refusing, listOf(rules)).pass() } }
                refusing.calls.map { it.second }.shouldContainExactly(first.limit)
            }

            test("two configs cannot limit one message") {
                val rules = config(Action::class to listOf(ThrottleRule(ThrottleLimit(1, MINUTE))))

                shouldThrow<IllegalArgumentException> { decorator(SpyLimiter(), listOf(rules, rules)) }
            }
        }

        context("the chain") {
            test("over the limit the caller gets the wait, and neither the handler nor the database is reached") {
                val database = TestDatabase.create()
                TenantMigrationRunner(database.owner()).migrateAll()
                val connections = AtomicInteger()
                val runtime =
                    object : DelegatingDataSource(database.role("app_runtime")) {
                        override fun getConnection(): Connection {
                            connections.incrementAndGet()
                            return super.getConnection()
                        }
                    }
                val handled = AtomicInteger()
                val handler =
                    object : MessageHandler<Action, Unit> {
                        override val messageType = Action::class

                        override fun handle(message: Action) {
                            handled.incrementAndGet()
                        }
                    }
                val security =
                    object : SecurityConfig {
                        override val permissions = mapOf<KClass<out Message<*>>, Permission?>(Action::class to null)
                        override val roles = emptyMap<Role, Set<Permission>>()
                    }

                limiter(TestValkey.shared.url).use { limiter ->
                    val bus =
                        CommandBus(
                            BusPipeline(
                                handlers = listOf(handler),
                                security = SecurityDecorator(listOf(security), NoDenials),
                                logger = LoggerDecorator(),
                                transaction = TransactionDecorator(TenantTransaction(runtime), PlatformTransaction(runtime)),
                                throttle =
                                    decorator(
                                        limiter,
                                        default = ThrottleLimit(2, MINUTE),
                                        secret = UUID.randomUUID().toString().toByteArray(),
                                    ),
                            ),
                        )

                    request {
                        repeat(2) { bus.dispatch(Action()) }
                        val failure = shouldThrow<TooManyRequestsException> { bus.dispatch(Action()) }

                        failure.retryAfter shouldBeGreaterThan Duration.ZERO
                        failure.retryAfter shouldBeLessThanOrEqualTo MINUTE
                    }
                }

                handled.get() shouldBe 2
                connections.get() shouldBe 2
            }
        }

        context("Valkey") {
            test("a bucket refuses over capacity and names the wait") {
                limiter(TestValkey.shared.url).use { limiter ->
                    val key = uniqueKey()

                    limiter.decide(key, capacity = 2) shouldBe RateDecision.Allowed
                    limiter.decide(key, capacity = 2) shouldBe RateDecision.Allowed
                    val refusal = limiter.decide(key, capacity = 2).shouldBeInstanceOf<RateDecision.Rejected>()

                    refusal.retryAfter shouldBeGreaterThan Duration.ZERO
                    refusal.retryAfter shouldBeLessThanOrEqualTo MINUTE
                }
            }

            test("two instances share one counter") {
                limiter(TestValkey.shared.url).use { first ->
                    limiter(TestValkey.shared.url).use { second ->
                        val key = uniqueKey()

                        first.decide(key, capacity = 3) shouldBe RateDecision.Allowed
                        second.decide(key, capacity = 3) shouldBe RateDecision.Allowed
                        first.decide(key, capacity = 3) shouldBe RateDecision.Allowed
                        second.decide(key, capacity = 3).shouldBeInstanceOf<RateDecision.Rejected>()
                    }
                }
            }

            test("a changed limit takes effect at once instead of waiting for the old bucket to expire") {
                limiter(TestValkey.shared.url).use { limiter ->
                    val key = uniqueKey()

                    limiter.decide(key, capacity = 1) shouldBe RateDecision.Allowed
                    limiter.decide(key, capacity = 1).shouldBeInstanceOf<RateDecision.Rejected>()

                    limiter.decide(key, capacity = 2) shouldBe RateDecision.Allowed
                }
            }

            test("an unreachable Valkey lets the request through and counts the failure") {
                val registry = SimpleMeterRegistry()
                val port = ServerSocket(0).use { it.localPort }

                limiter("redis://127.0.0.1:$port", registry).use { limiter ->
                    limiter.decide(uniqueKey()) shouldBe RateDecision.Allowed
                }

                registry.counter(MESSAGE_FAILURES).count() shouldBe 1.0
            }

            test("a Valkey that accepts and stays silent costs one short timeout, and the breaker spares the rest") {
                val registry = SimpleMeterRegistry()
                val clock = MovableClock()

                ServerSocket(0).use { silent ->
                    limiter("redis://127.0.0.1:${silent.localPort}", registry, clock, SHORT, SHORT).use { limiter ->
                        measureTime { limiter.decide(uniqueKey()) shouldBe RateDecision.Allowed } shouldBeLessThan 3.seconds

                        measureTime { repeat(100) { limiter.decide(uniqueKey()) shouldBe RateDecision.Allowed } } shouldBeLessThan 1.seconds
                        registry.counter(MESSAGE_FAILURES).count() shouldBe 1.0

                        clock.offset = COOLDOWN.plusSeconds(1)
                        limiter.decide(uniqueKey()) shouldBe RateDecision.Allowed
                        registry.counter(MESSAGE_FAILURES).count() shouldBe 2.0
                    }
                }
            }

            test("connecting gets its own timeout, because TLS and AUTH on a cold JVM outlast a command") {
                ServerSocket(0).use { silent ->
                    limiter("redis://127.0.0.1:${silent.localPort}", timeout = SHORT, connectTimeout = CONNECT).use { limiter ->
                        val elapsed = measureTime { limiter.decide(uniqueKey()) shouldBe RateDecision.Allowed }

                        elapsed.toJavaDuration() shouldBeGreaterThan CONNECT.dividedBy(2)
                    }
                }
            }

            test("limits return after a hung Valkey recovers") {
                val registry = SimpleMeterRegistry()
                val clock = MovableClock()

                TestValkey.start().use { server ->
                    limiter(server.url, registry, clock, timeout = CONNECT).use { limiter ->
                        val key = uniqueKey()
                        limiter.decide(key) shouldBe RateDecision.Allowed

                        server.pause()
                        measureTime { limiter.decide(key) shouldBe RateDecision.Allowed } shouldBeLessThan 3.seconds
                        registry.counter(MESSAGE_FAILURES).count() shouldBe 1.0

                        server.unpause()
                        limiter.decide(key) shouldBe RateDecision.Allowed
                        clock.offset = COOLDOWN.plusSeconds(1)

                        limiter.decide(key).shouldBeInstanceOf<RateDecision.Rejected>()
                    }
                }
            }
        }
    })
