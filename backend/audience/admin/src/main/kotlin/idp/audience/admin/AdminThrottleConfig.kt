package idp.audience.admin

import idp.audience.admin.application.command.InviteUser
import idp.audience.admin.application.command.ResendInvitation
import idp.sharedkernel.bus.Message
import idp.sharedkernel.throttle.ThrottleConfig
import idp.sharedkernel.throttle.ThrottleDimension
import idp.sharedkernel.throttle.ThrottleLimit
import idp.sharedkernel.throttle.ThrottleRule
import java.time.Duration
import kotlin.reflect.KClass

private val HOUR: Duration = Duration.ofHours(1)

/** Each sends a mail from the address every tenant shares, so one stolen admin session must not spend its reputation. */
object AdminThrottleConfig : ThrottleConfig {
    override val rules: Map<KClass<out Message<*>>, List<ThrottleRule>> =
        mapOf(
            InviteUser::class to listOf(ThrottleRule(ThrottleLimit(100, HOUR), ThrottleDimension.USER)),
            ResendInvitation::class to listOf(ThrottleRule(ThrottleLimit(10, HOUR), ThrottleDimension.USER)),
        )
}
