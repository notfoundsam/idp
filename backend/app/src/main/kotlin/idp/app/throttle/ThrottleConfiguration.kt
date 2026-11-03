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
        @Value("\${idp.throttle.multiplier}") multiplier: Long,
        registry: MeterRegistry,
    ): RateLimiter {
        val limiter = ValkeyRateLimiter(url, timeout, connectTimeout, cooldown, registry)
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
