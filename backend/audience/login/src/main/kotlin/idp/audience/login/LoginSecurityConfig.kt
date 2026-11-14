package idp.audience.login

import idp.audience.login.application.command.BeginPasskeyEnrollment
import idp.audience.login.application.command.BeginPasskeyLogin
import idp.audience.login.application.command.BeginTotpEnrollment
import idp.audience.login.application.command.CompleteFlow
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
import idp.audience.login.application.query.GetFlow
import idp.sharedkernel.bus.Message
import idp.sharedkernel.security.Permission
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.security.UserRole
import kotlin.reflect.KClass

private val CHANGE_EMAIL = Permission("email.change")

/**
 * Nearly all of it is public: nobody has proved anything before a login, the flow itself decides what comes next, and a one-time token
 * stands in for a principal. Only asking to change the email needs a session.
 */
object LoginSecurityConfig : SecurityConfig {
    override val permissions: Map<KClass<out Message<*>>, Permission?> =
        mapOf(
            StartFlow::class to null,
            GetFlow::class to null,
            SubmitLogin::class to null,
            SubmitPassword::class to null,
            SubmitTotp::class to null,
            SubmitRecoveryCode::class to null,
            BeginPasskeyLogin::class to null,
            SubmitPasskey::class to null,
            BeginTotpEnrollment::class to null,
            ConfirmTotpEnrollment::class to null,
            BeginPasskeyEnrollment::class to null,
            FinishPasskeyEnrollment::class to null,
            CompleteFlow::class to null,
            AcceptInvitation::class to null,
            RequestPasswordReset::class to null,
            ConfirmPasswordReset::class to null,
            ConfirmEmailChange::class to null,
            RequestEmailChange::class to CHANGE_EMAIL,
        )

    override val roles: Map<Role, Set<Permission>> = UserRole.entries.associate { it.role to setOf(CHANGE_EMAIL) }
}
