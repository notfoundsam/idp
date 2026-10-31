package idp.sharedkernel.throttle

import idp.sharedkernel.bus.Message
import java.time.Duration
import kotlin.reflect.KClass

data class ThrottleLimit(
    val capacity: Long,
    val period: Duration,
)

enum class ThrottleDimension {
    IP,
    USER,
    TENANT,
}

/** Without a dimension the rule follows the caller: `USER` with a principal, `IP` otherwise. */
data class ThrottleRule(
    val limit: ThrottleLimit,
    val dimension: ThrottleDimension? = null,
)

/** Only for messages that differ from the default limit. */
interface ThrottleConfig {
    /** Rules apply in order, so the narrowest goes first. */
    val rules: Map<KClass<out Message<*>>, List<ThrottleRule>>
}
