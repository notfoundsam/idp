package idp.app.throttle

import idp.sharedkernel.bus.decorators.ThrottleDecorator
import idp.sharedkernel.throttle.RateLimiter
import idp.sharedkernel.throttle.ScaledRateLimiter
import idp.sharedkernel.throttle.ThrottleConfig
import idp.sharedkernel.throttle.ThrottleKeys
import idp.sharedkernel.throttle.ThrottleLimit
import idp.sharedkernel.throttle.adapter.ValkeyRateLimiter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import java.time.Duration

private const val PROD = "prod"
private const val MIN_PROD_KEY_LENGTH = 32
private val UNTRUSTED_CLIENT_IP_HEADERS = listOf("X-Forwarded-For", "Forwarded")

@Configuration(proxyBeanMethods = false)
class ThrottleGuard(
    environment: Environment,
    @Value("\${idp.throttle.enabled}") enabled: Boolean,
    @Value("\${idp.throttle.multiplier}") multiplier: Long,
) {
    init {
        val prod = environment.acceptsProfiles(Profiles.of(PROD))
        check(enabled || !prod) { "THROTTLE_ENABLED=false is refused in the $PROD profile" }
        check(multiplier == 1L || !prod) { "THROTTLE_MULTIPLIER=$multiplier is refused in the $PROD profile" }
        if (prod) {
            check(environment.getRequiredProperty("idp.throttle.valkey.url").startsWith("rediss://")) {
                "A VALKEY_URL without TLS is refused in the $PROD profile"
            }
            check(environment.getRequiredProperty("idp.throttle.valkey.password").isNotEmpty()) {
                "An empty VALKEY_PASSWORD is refused in the $PROD profile"
            }
            check(environment.getRequiredProperty("idp.throttle.ip-hmac-key").length >= MIN_PROD_KEY_LENGTH) {
                "A THROTTLE_IP_HMAC_KEY shorter than $MIN_PROD_KEY_LENGTH characters is refused in the $PROD profile"
            }
            val clientIpHeader = environment.getProperty("idp.client-ip.trusted-header").orEmpty()
            check(clientIpHeader.isNotBlank()) {
                "An empty CLIENT_IP_HEADER is refused in the $PROD profile, every client would share the address of the load balancer"
            }
            check(UNTRUSTED_CLIENT_IP_HEADERS.none { it.equals(clientIpHeader.trim(), ignoreCase = true) }) {
                "CLIENT_IP_HEADER=$clientIpHeader is refused in the $PROD profile, a proxy appends to what the client sent in it"
            }
        }
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("idp.throttle.enabled", havingValue = "true")
class ThrottleConfiguration {
    @Bean
    fun rateLimiter(
        @Value("\${idp.throttle.valkey.url}") url: String,
        @Value("\${idp.throttle.valkey.timeout}") timeout: Duration,
        @Value("\${idp.throttle.valkey.connect-timeout}") connectTimeout: Duration,
        @Value("\${idp.throttle.valkey.cooldown}") cooldown: Duration,
        @Value("\${idp.throttle.valkey.password}") password: String,
        @Value("\${idp.throttle.valkey.tls-ca}") tlsCa: String,
        @Value("\${idp.throttle.multiplier}") multiplier: Long,
        registry: MeterRegistry,
    ): RateLimiter {
        val limiter = ValkeyRateLimiter(url, timeout, connectTimeout, cooldown, registry, password = password, tlsCa = tlsCa)
        return if (multiplier == 1L) limiter else ScaledRateLimiter(limiter, multiplier)
    }

    @Bean
    fun throttleKeys(
        @Value("\${idp.throttle.ip-hmac-key}") secret: String,
    ) = ThrottleKeys(secret.toByteArray())

    @Bean
    fun throttleDecorator(
        configs: ObjectProvider<ThrottleConfig>,
        @Value("\${idp.throttle.default.capacity}") capacity: Long,
        @Value("\${idp.throttle.default.period}") period: Duration,
        limiter: RateLimiter,
        keys: ThrottleKeys,
    ) = ThrottleDecorator(configs.orderedStream().toList(), ThrottleLimit(capacity, period), limiter, keys)
}
