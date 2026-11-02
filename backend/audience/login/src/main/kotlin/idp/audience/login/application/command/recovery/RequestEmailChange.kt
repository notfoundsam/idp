package idp.audience.login.application.command.recovery

import idp.audience.login.application.command.clientIp
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.security.RequiresFreshAuthentication
import idp.sharedkernel.security.SecurityContext

/** Not a data class: `toString` must not print the address. The address is the way back into an account, so an old session is not enough. */
class RequestEmailChange(
    val newEmail: String,
) : Command<Unit>,
    RequiresFreshAuthentication

class RequestEmailChangeHandler(
    private val identity: IdentityService,
) : MessageHandler<RequestEmailChange, Unit> {
    override val messageType = RequestEmailChange::class

    /** Security let the message through, so the actor is the user of a live session. */
    override fun handle(message: RequestEmailChange) {
        val actor = checkNotNull(SecurityContext.currentOrNull()).actor as Actor.User
        identity.requestEmailChange(actor.id, message.newEmail, actor, clientIp())
    }
}
