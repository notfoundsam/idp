package idp.audience.account

import idp.audience.account.application.command.ChangePassword
import idp.audience.account.application.command.ConfirmTotpSetup
import idp.audience.account.application.command.FinishPasskeySetup
import idp.sharedkernel.bus.Message
import idp.sharedkernel.throttle.ThrottleConfig
import idp.sharedkernel.throttle.ThrottleDimension
import idp.sharedkernel.throttle.ThrottleLimit
import idp.sharedkernel.throttle.ThrottleRule
import java.time.Duration
import kotlin.reflect.KClass

/** The same pace as a credential at login. Only a session gets here, so the user is the narrowest key. */
private val CREDENTIAL = listOf(ThrottleRule(ThrottleLimit(10, Duration.ofMinutes(1)), ThrottleDimension.USER))

object AccountThrottleConfig : ThrottleConfig {
    override val rules: Map<KClass<out Message<*>>, List<ThrottleRule>> =
        mapOf(
            ChangePassword::class to CREDENTIAL,
            ConfirmTotpSetup::class to CREDENTIAL,
            FinishPasskeySetup::class to CREDENTIAL,
        )
}
