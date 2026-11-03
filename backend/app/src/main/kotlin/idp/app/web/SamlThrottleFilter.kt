package idp.app.web

import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.throttle.RateDecision
import idp.sharedkernel.throttle.RateLimiter
import idp.sharedkernel.throttle.ThrottleDimension
import idp.sharedkernel.throttle.ThrottleKeys
import idp.sharedkernel.throttle.ThrottleLimit
import idp.sharedkernel.throttle.ThrottleRule
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

private const val METADATA_PATH = "/saml/metadata"
private const val SSO_SCOPE = "saml.sso"
private const val METADATA_SCOPE = "saml.metadata"
private const val MILLIS_PER_SECOND = 1000L

private val MINUTE: Duration = Duration.ofMinutes(1)

private val securityEvents = LoggerFactory.getLogger("idp.security")

/** A login through an SP takes two requests here, and a person may come back a few times in a minute. Like starting a flow. */
val SSO_RULES =
    listOf(
        ThrottleRule(ThrottleLimit(30, MINUTE), ThrottleDimension.IP),
        ThrottleRule(ThrottleLimit(6_000, MINUTE), ThrottleDimension.TENANT),
    )

/** SPs fetch metadata on a schedule, and it costs a read. No tenant bucket, so nobody can keep the SPs of a tenant from it. */
val METADATA_RULES = listOf(ThrottleRule(ThrottleLimit(120, MINUTE), ThrottleDimension.IP))

/**
 * SAML does not pass the bus, so its limits stand here, on the same port. Without the beans throttling is off, which only
 * `THROTTLE_ENABLED=false` does. A refusal goes to the log of security events like one of the bus.
 */
class SamlThrottleFilter(
    private val limiter: ObjectProvider<RateLimiter>,
    private val keys: ObjectProvider<ThrottleKeys>,
    private val ssoRules: List<ThrottleRule> = SSO_RULES,
    private val metadataRules: List<ThrottleRule> = METADATA_RULES,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val limiter = limiter.ifAvailable
        val keys = keys.ifAvailable
        if (limiter == null || keys == null) return chain.doFilter(request, response)
        val metadata = request.requestURI == METADATA_PATH
        val scope = if (metadata) METADATA_SCOPE else SSO_SCOPE
        (if (metadata) metadataRules else ssoRules).forEach { rule ->
            val decision = limiter.acquire(keys.key(scope, rule.dimension), rule.limit)
            if (decision is RateDecision.Rejected) {
                securityEvents.warn(
                    "event=throttle.rejected message={} dimension={} tenant={}",
                    scope,
                    rule.dimension,
                    TenantContext.current().tenantId,
                )
                response.status = HttpStatus.TOO_MANY_REQUESTS.value()
                response.setHeader(HttpHeaders.RETRY_AFTER, secondsUp(decision.retryAfter).toString())
                return
            }
        }
        chain.doFilter(request, response)
    }

    private fun secondsUp(duration: Duration): Long = (duration.toMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND

    companion object {
        /** Needs the tenant of the request. */
        const val ORDER = SessionFilter.ORDER + 1
    }
}
