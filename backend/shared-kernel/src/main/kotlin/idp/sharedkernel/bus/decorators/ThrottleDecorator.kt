package idp.sharedkernel.bus.decorators

import idp.sharedkernel.bus.Message
import idp.sharedkernel.throttle.RateDecision
import idp.sharedkernel.throttle.RateLimiter
import idp.sharedkernel.throttle.ThrottleConfig
import idp.sharedkernel.throttle.ThrottleKeys
import idp.sharedkernel.throttle.ThrottleLimit
import idp.sharedkernel.throttle.ThrottleRule
import idp.sharedkernel.throttle.TooManyRequestsException
import kotlin.reflect.KClass

class ThrottleDecorator(
    configs: List<ThrottleConfig>,
    default: ThrottleLimit,
    private val limiter: RateLimiter,
    private val keys: ThrottleKeys,
) : BusDecorator {
    private val defaultRules = listOf(ThrottleRule(default))
    private val rules: Map<KClass<out Message<*>>, List<ThrottleRule>> =
        configs.fold(emptyMap()) { all, config ->
            val repeated = all.keys intersect config.rules.keys
            require(repeated.isEmpty()) { "One ThrottleConfig limits a message: $repeated" }
            all + config.rules
        }

    /** Stops at the first refusal, so a blocked IP cannot spend the bucket of its whole tenant. */
    override fun <R> around(
        message: Message<R>,
        next: () -> R,
    ): R {
        rules[message::class].orEmpty().ifEmpty { defaultRules }.forEach { rule ->
            val decision = limiter.acquire(keys.key(message.javaClass.name, rule.dimension), rule.limit)
            if (decision is RateDecision.Rejected) throw TooManyRequestsException(decision.retryAfter)
        }
        return next()
    }
}
