package idp.audience.account.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.SessionNotFound
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.security.RequiresFreshAuthentication
import idp.sharedkernel.security.SecurityContext
import java.util.UUID

/** A stolen session must not be able to end the one of the owner, so ending a session needs a fresh login. */
class EndSession(
    val id: UUID,
) : Command<Unit>,
    RequiresFreshAuthentication

/** The current session ends by logging out, so here it reads as missing like a session of someone else. */
class EndSessionHandler(
    private val authentication: AuthenticationService,
) : MessageHandler<EndSession, Unit> {
    override val messageType = EndSession::class

    override fun handle(message: EndSession) {
        if (message.id == currentSession()) throw SessionNotFound(message.id)
        val user = currentUser()
        authentication.revokeSession(user.id, message.id, user, clientIp())
    }
}

/** Every session of the user but the current one, together with their logins in progress. */
class EndOtherSessions :
    Command<Unit>,
    RequiresFreshAuthentication

class EndOtherSessionsHandler(
    private val authentication: AuthenticationService,
) : MessageHandler<EndOtherSessions, Unit> {
    override val messageType = EndOtherSessions::class

    override fun handle(message: EndOtherSessions) {
        val user = currentUser()
        authentication.revokeUserSessions(user.id, user, clientIp(), kept = currentSession())
    }
}

/** Public, so a browser whose session is already gone still gets its cookies and storage cleared. */
class Logout : Command<Unit>

class LogoutHandler(
    private val authentication: AuthenticationService,
) : MessageHandler<Logout, Unit> {
    override val messageType = Logout::class

    override fun handle(message: Logout) {
        val principal = SecurityContext.currentOrNull() ?: return
        val user = principal.actor as? Actor.User ?: return
        authentication.logout(user.id, principal.sessionId ?: return, clientIp())
    }
}
