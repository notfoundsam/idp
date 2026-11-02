package idp.audience.login.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowAccess
import idp.authentication.application.FlowView
import idp.authentication.application.LoginFactor
import idp.identity.application.TotpEnrollment
import idp.identity.application.TotpService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope

/** The recovery codes appear only here, so a user who enrolls during a login leaves it with a way back in. */
class EnrolledFactor(
    val view: FlowView,
    val recoveryCodes: List<String>,
) {
    override fun toString(): String = "EnrolledFactor(***)"
}

class BeginTotpEnrollment(
    val flow: FlowAccess,
) : Command<TotpEnrollment>

class BeginTotpEnrollmentHandler(
    private val authentication: AuthenticationService,
    private val totp: TotpService,
) : MessageHandler<BeginTotpEnrollment, TotpEnrollment> {
    override val messageType = BeginTotpEnrollment::class

    override fun handle(message: BeginTotpEnrollment): TotpEnrollment =
        totp.enroll(authentication.enrollingUser(message.flow, LoginFactor.TOTP))
}

/** Not a data class: `toString` must not print the code. */
class ConfirmTotpEnrollment(
    val flow: FlowAccess,
    val code: String,
) : Command<EnrolledFactor> {
    override val transaction = TransactionScope.NONE
}

class ConfirmTotpEnrollmentHandler(
    private val authentication: AuthenticationService,
    private val totp: TotpService,
    private val attempts: LoginAttempts,
) : MessageHandler<ConfirmTotpEnrollment, EnrolledFactor> {
    override val messageType = ConfirmTotpEnrollment::class

    override fun handle(message: ConfirmTotpEnrollment): EnrolledFactor =
        attempts.enrollment(message.flow, LoginFactor.TOTP) { userId ->
            val actor = Actor.User(userId)
            if (totp.confirm(userId, message.code, actor, clientIp())) {
                val codes = totp.issueRecoveryCodes(userId, actor, clientIp())
                EnrolledFactor(authentication.enrolled(message.flow, LoginFactor.TOTP), codes)
            } else {
                null
            }
        }
}
