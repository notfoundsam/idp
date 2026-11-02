package idp.audience.login

import idp.audience.login.application.command.BeginPasskeyEnrollment
import idp.audience.login.application.command.BeginPasskeyLogin
import idp.audience.login.application.command.BeginTotpEnrollment
import idp.audience.login.application.command.ConfirmTotpEnrollment
import idp.audience.login.application.command.FinishPasskeyEnrollment
import idp.audience.login.application.command.StartFlow
import idp.audience.login.application.command.SubmitLogin
import idp.audience.login.application.command.SubmitPasskey
import idp.audience.login.application.command.SubmitPassword
import idp.audience.login.application.command.SubmitRecoveryCode
import idp.audience.login.application.command.SubmitTotp
import idp.audience.login.application.command.recovery.AcceptInvitation
import idp.audience.login.application.command.recovery.ConfirmEmailChange
import idp.audience.login.application.command.recovery.ConfirmPasswordReset
import idp.audience.login.application.command.recovery.RequestEmailChange
import idp.audience.login.application.command.recovery.RequestPasswordReset
import idp.sharedkernel.bus.Message
import idp.sharedkernel.throttle.ThrottleConfig
import idp.sharedkernel.throttle.ThrottleDimension
import idp.sharedkernel.throttle.ThrottleLimit
import idp.sharedkernel.throttle.ThrottleRule
import java.time.Duration
import kotlin.reflect.KClass

private val MINUTE: Duration = Duration.ofMinutes(1)
private val HOUR: Duration = Duration.ofHours(1)

/** A flow asks for several of these calls, and a person mistypes. */
private val NAVIGATION =
    listOf(
        ThrottleRule(ThrottleLimit(30, MINUTE), ThrottleDimension.IP),
        ThrottleRule(ThrottleLimit(6_000, MINUTE), ThrottleDimension.TENANT),
    )

/**
 * IP comes first, so a blocked address spends nothing of its tenant. The tenant bucket holds a hundred addresses at full speed: one
 * close to the limit of a single address would let a botnet shut every employee out by guessing.
 */
private val CREDENTIAL =
    listOf(
        ThrottleRule(ThrottleLimit(10, MINUTE), ThrottleDimension.IP),
        ThrottleRule(ThrottleLimit(1_000, MINUTE), ThrottleDimension.TENANT),
    )

/** Each of these sends a mail to a mailbox the caller may not own. A person needs one or two. */
private val MAIL =
    listOf(
        ThrottleRule(ThrottleLimit(5, HOUR), ThrottleDimension.IP),
        ThrottleRule(ThrottleLimit(500, HOUR), ThrottleDimension.TENANT),
    )

object LoginThrottleConfig : ThrottleConfig {
    override val rules: Map<KClass<out Message<*>>, List<ThrottleRule>> =
        mapOf(
            StartFlow::class to NAVIGATION,
            SubmitLogin::class to NAVIGATION,
            BeginPasskeyLogin::class to NAVIGATION,
            BeginTotpEnrollment::class to NAVIGATION,
            BeginPasskeyEnrollment::class to NAVIGATION,
            SubmitPassword::class to CREDENTIAL,
            SubmitTotp::class to CREDENTIAL,
            SubmitRecoveryCode::class to CREDENTIAL,
            SubmitPasskey::class to CREDENTIAL,
            ConfirmTotpEnrollment::class to CREDENTIAL,
            FinishPasskeyEnrollment::class to CREDENTIAL,
            AcceptInvitation::class to CREDENTIAL,
            ConfirmPasswordReset::class to CREDENTIAL,
            ConfirmEmailChange::class to CREDENTIAL,
            RequestPasswordReset::class to MAIL,
            RequestEmailChange::class to MAIL,
        )
}
