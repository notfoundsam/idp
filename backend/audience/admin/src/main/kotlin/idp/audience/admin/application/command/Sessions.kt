package idp.audience.admin.application.command

import idp.authentication.application.AuthenticationService
import idp.identity.application.IdentityService
import idp.identity.application.UserNotFound
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import java.util.UUID

/** Every session of the user and their logins in progress. An admin who ends their own keeps the one they act from. */
class EndUserSessions(
    val userId: UUID,
) : Command<Unit>

class EndUserSessionsHandler(
    private val identity: IdentityService,
    private val authentication: AuthenticationService,
) : MessageHandler<EndUserSessions, Unit> {
    override val messageType = EndUserSessions::class

    override fun handle(message: EndUserSessions) {
        identity.user(message.userId) ?: throw UserNotFound(message.userId)
        authentication.revokeUserSessions(message.userId, currentAdmin(), clientIp(), kept = currentSession())
    }
}

/** For a tenant that fears a breach, so the session of the admin ends too. */
class EndAllSessions : Command<Unit>

class EndAllSessionsHandler(
    private val authentication: AuthenticationService,
) : MessageHandler<EndAllSessions, Unit> {
    override val messageType = EndAllSessions::class

    override fun handle(message: EndAllSessions) = authentication.revokeAllSessions(currentAdmin(), clientIp())
}
