package idp.audience.login.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowAccess
import idp.authentication.application.FlowView
import idp.authentication.application.LoginFactor
import idp.identity.application.TotpService
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope

/** Not a data class: `toString` must not print the code. */
class SubmitTotp(
    val flow: FlowAccess,
    val code: String,
) : Command<FlowView> {
    override val transaction = TransactionScope.NONE
}

class SubmitTotpHandler(
    private val authentication: AuthenticationService,
    private val totp: TotpService,
    private val attempts: LoginAttempts,
) : MessageHandler<SubmitTotp, FlowView> {
    override val messageType = SubmitTotp::class

    override fun handle(message: SubmitTotp): FlowView =
        attempts.step(message.flow, LoginFactor.TOTP) { attempt ->
            val matches = totp.verify(checkNotNull(attempt.userId), message.code)
            if (matches && attempt.active) authentication.totpPassed(message.flow) else null
        }
}
