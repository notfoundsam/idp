package idp.audience.login.application.command.recovery

import idp.audience.login.application.command.clientIp
import idp.identity.application.IdentityService
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler

/** Not a data class: `toString` must not print the login. */
class RequestPasswordReset(
    val login: String,
) : Command<Unit>

class RequestPasswordResetHandler(
    private val identity: IdentityService,
) : MessageHandler<RequestPasswordReset, Unit> {
    override val messageType = RequestPasswordReset::class

    override fun handle(message: RequestPasswordReset) = identity.requestPasswordReset(message.login, clientIp())
}
