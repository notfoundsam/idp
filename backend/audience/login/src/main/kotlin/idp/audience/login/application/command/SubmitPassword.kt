package idp.audience.login.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowAccess
import idp.authentication.application.FlowView
import idp.authentication.application.LoginFactor
import idp.identity.application.PasswordService
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope

/** Not a data class: `toString` must not print the password. */
class SubmitPassword(
    val flow: FlowAccess,
    val password: String,
) : Command<FlowView> {
    override val transaction = TransactionScope.NONE
}

class SubmitPasswordHandler(
    private val authentication: AuthenticationService,
    private val passwords: PasswordService,
    private val attempts: LoginAttempts,
) : MessageHandler<SubmitPassword, FlowView> {
    override val messageType = SubmitPassword::class

    /** The status comes after the hash, so a blocked user answers like a wrong password and just as slowly. */
    override fun handle(message: SubmitPassword): FlowView =
        attempts.step(message.flow, LoginFactor.PASSWORD) { attempt ->
            val matches = passwords.verify(attempt.userId, message.password)
            if (matches && attempt.active) authentication.passwordPassed(message.flow) else null
        }
}
