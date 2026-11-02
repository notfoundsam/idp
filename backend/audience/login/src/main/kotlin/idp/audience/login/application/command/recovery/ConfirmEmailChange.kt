package idp.audience.login.application.command.recovery

import idp.audience.login.application.command.clientIp
import idp.identity.application.EmailChangeNotValid
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope

/** Not a data class: `toString` must not print the token. Public, because the link may open on a device without the session. */
class ConfirmEmailChange(
    val token: String,
) : Command<Unit> {
    override val transaction = TransactionScope.NONE
}

class ConfirmEmailChangeHandler(
    private val identity: IdentityService,
    private val audit: AuditLog,
) : MessageHandler<ConfirmEmailChange, Unit> {
    override val messageType = ConfirmEmailChange::class

    override fun handle(message: ConfirmEmailChange) {
        try {
            identity.confirmEmailChange(message.token, clientIp())
        } catch (refusal: EmailChangeNotValid) {
            audit.tokenRefused(EMAIL_CHANGE)
            throw refusal
        }
    }
}
