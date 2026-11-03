package idp.app.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.throttle.RateDecision
import idp.sharedkernel.throttle.RateLimiter
import idp.sharedkernel.throttle.ThrottleDimension
import idp.sharedkernel.throttle.ThrottleKeys
import idp.sharedkernel.throttle.ThrottleLimit
import idp.sharedkernel.throttle.ThrottleRule
import idp.sharedkernel.web.ClientIp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val MINUTE: Duration = Duration.ofMinutes(1)
private const val IP_LIMIT = 2L
private const val TENANT_LIMIT = 3L
private const val METADATA_LIMIT = 4L

/** Counts in memory with the same contract as Valkey: a bucket per key, refused beyond its capacity. */
private class CountingLimiter : RateLimiter {
    val keys = ConcurrentHashMap<String, Long>()

    override fun acquire(
        key: String,
        limit: ThrottleLimit,
    ): RateDecision =
        if (keys.merge(key, 1, Long::plus)!! >
            limit.capacity
        ) {
            RateDecision.Rejected(Duration.ofMillis(1500))
        } else {
            RateDecision.Allowed
        }
}

private class Result(
    val response: MockHttpServletResponse,
    val passed: Boolean,
)

private class Throttled(
    beans: Boolean = true,
) {
    val limiter = CountingLimiter()
    private val factory =
        DefaultListableBeanFactory().apply {
            if (beans) {
                registerSingleton("limiter", limiter)
                registerSingleton("keys", ThrottleKeys(UUID.randomUUID().toString().toByteArray()))
            }
        }
    private val filter =
        SamlThrottleFilter(
            factory.getBeanProvider(RateLimiter::class.java),
            factory.getBeanProvider(ThrottleKeys::class.java),
            listOf(
                ThrottleRule(ThrottleLimit(IP_LIMIT, MINUTE), ThrottleDimension.IP),
                ThrottleRule(ThrottleLimit(TENANT_LIMIT, MINUTE), ThrottleDimension.TENANT),
            ),
            listOf(ThrottleRule(ThrottleLimit(METADATA_LIMIT, MINUTE), ThrottleDimension.IP)),
        )

    fun request(
        path: String = "/saml/sso",
        ip: String = "198.51.100.7",
        tenant: UUID = ACME,
    ): Result {
        val request = MockHttpServletRequest("GET", path)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        TenantContext.with(TenantContext(tenant, "kek")) { ClientIp.with(ClientIp(ip)) { filter.doFilter(request, response, chain) } }
        return Result(response, chain.request != null)
    }
}

private val ACME: UUID = UUID.randomUUID()
private val GLOBEX: UUID = UUID.randomUUID()

class SamlThrottleFilterTest :
    FunSpec({
        test("lets an address through up to its limit and answers 429 beyond it") {
            val throttled = Throttled()
            repeat(IP_LIMIT.toInt()) { throttled.request().passed shouldBe true }

            val refused = throttled.request()

            refused.passed shouldBe false
            refused.response.status shouldBe 429
            refused.response.getHeader("Retry-After") shouldBe "2"
        }

        test("refuses a new address once the tenant has spent its bucket") {
            val throttled = Throttled()
            (1..TENANT_LIMIT).forEach { throttled.request(ip = "198.51.100.$it").passed shouldBe true }

            throttled.request(ip = "203.0.113.9").response.status shouldBe 429
        }

        test("a tenant spends nothing of another") {
            val throttled = Throttled()
            repeat(TENANT_LIMIT.toInt() + 1) { throttled.request(ip = "198.51.100.$it") }

            throttled.request(tenant = GLOBEX).passed shouldBe true
        }

        test("an address refused by its own limit spends nothing of the tenant") {
            val throttled = Throttled()
            repeat(10) { throttled.request(ip = "198.51.100.1") }

            throttled.request(ip = "198.51.100.2").passed shouldBe true
        }

        test("metadata counts apart, under a softer limit without a tenant bucket") {
            val throttled = Throttled()
            repeat(TENANT_LIMIT.toInt() + 1) { throttled.request(ip = "198.51.100.$it") }

            repeat(METADATA_LIMIT.toInt()) { throttled.request("/saml/metadata").passed shouldBe true }
            throttled.request("/saml/metadata").response.status shouldBe 429
            throttled.request("/saml/metadata", ip = "203.0.113.9").passed shouldBe true
        }

        test("the limits of production are softer for metadata than for SSO") {
            val sso = SSO_RULES.single { it.dimension == ThrottleDimension.IP }.limit.capacity
            val metadata = METADATA_RULES.single().limit.capacity

            metadata.toInt() shouldBeGreaterThan sso.toInt()
            METADATA_RULES.map { it.dimension }.filter { it == ThrottleDimension.TENANT }.shouldBeEmpty()
            SSO_RULES.first().dimension shouldBe ThrottleDimension.IP
        }

        test("keys never hold the address") {
            val throttled = Throttled()
            throttled.request(ip = "198.51.100.77")

            throttled.limiter.keys.keys
                .forEach { it shouldNotContain "198.51.100.77" }
            throttled.limiter.keys.keys
                .single { it.contains(":IP:saml.sso:") }
                .shouldNotBeNull()
        }

        test("a refusal reaches the log of security events without the address") {
            val throttled = Throttled()
            val logger = LoggerFactory.getLogger("idp.security") as Logger
            val events = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(events)
            try {
                repeat(IP_LIMIT.toInt() + 1) { throttled.request(ip = "198.51.100.88") }
            } finally {
                logger.detachAppender(events)
            }

            val event = events.list.single().formattedMessage
            event shouldContain "event=throttle.rejected"
            event shouldContain "message=saml.sso"
            event shouldContain "tenant=$ACME"
            event shouldNotContain "198.51.100.88"
        }

        test("without the beans of THROTTLE_ENABLED=true everything passes") {
            val throttled = Throttled(beans = false)

            repeat(10) {
                val result = throttled.request()
                result.passed shouldBe true
                result.response.getHeader("Retry-After").shouldBeNull()
            }
        }
    })
