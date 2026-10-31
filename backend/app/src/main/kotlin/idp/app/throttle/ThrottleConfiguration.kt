package idp.app.throttle

import idp.sharedkernel.bus.decorators.ThrottleDecorator
import idp.sharedkernel.throttle.RateLimiter
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
) {
    init {
        check(enabled || !environment.acceptsProfiles(Profiles.of(PROD))) { "THROTTLE_ENABLED=false is refused in the $PROD profile" }
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("idp.throttle.enabled", havingValue = "true")
class ThrottleConfiguration {
    @Bean
    fun rateLimiter(
        @Value("\${idp.throttle.valkey.url}") url: String,
        @Value("\${idp.throttle.valkey.timeout}") timeout: Duration,
        @Value("\${idp.throttle.valkey.cooldown}") cooldown: Duration,
        registry: MeterRegistry,
    ) = ValkeyRateLimiter(url, timeout, cooldown, registry)

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
