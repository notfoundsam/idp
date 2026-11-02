package idp.audience.login.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowAccess
import idp.authentication.application.FlowView
import idp.authentication.application.LoginFactor
import idp.identity.application.TotpService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope

/** Not a data class: `toString` must not print the code. */
class SubmitRecoveryCode(
    val flow: FlowAccess,
    val code: String,
) : Command<FlowView> {
    override val transaction = TransactionScope.NONE
}

class SubmitRecoveryCodeHandler(
    private val authentication: AuthenticationService,
    private val totp: TotpService,
    private val attempts: LoginAttempts,
) : MessageHandler<SubmitRecoveryCode, FlowView> {
    override val messageType = SubmitRecoveryCode::class

    /** The status comes first here: unlike a TOTP step, a recovery code does not come back, and this login would not use it. */
    override fun handle(message: SubmitRecoveryCode): FlowView =
        attempts.step(message.flow, LoginFactor.RECOVERY_CODE) { attempt ->
            val userId = checkNotNull(attempt.userId)
            if (attempt.active && totp.useRecoveryCode(userId, message.code, Actor.User(userId), clientIp())) {
                authentication.recoveryCodeUsed(message.flow)
            } else {
                null
            }
        }
}
