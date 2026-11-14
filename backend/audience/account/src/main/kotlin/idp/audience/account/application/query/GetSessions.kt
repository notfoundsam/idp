package idp.audience.account.application.query

import idp.audience.account.application.command.currentRole
import idp.audience.account.application.command.currentSession
import idp.audience.account.application.command.currentUser
import idp.authentication.application.AuthenticationService
import idp.authentication.application.UserSessionView
import idp.identity.application.IdentityService
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.Query
import idp.sharedkernel.security.UserRole
import java.util.UUID

/** Not a data class: `toString` must not print the email, the IPs or the User-Agents. */
class AccountSessions(
    val email: String,
    val role: UserRole,
    val sessions: List<UserSessionView>,
    val current: UUID,
)

/** Names nobody: the user is the one of the session. */
class GetSessions : Query<AccountSessions>

/** One query over `identity` and `authentication`, so the page reads both in one transaction. */
class GetSessionsHandler(
    private val identity: IdentityService,
    private val authentication: AuthenticationService,
) : MessageHandler<GetSessions, AccountSessions> {
    override val messageType = GetSessions::class

    override fun handle(message: GetSessions): AccountSessions {
        val user = currentUser()
        return AccountSessions(checkNotNull(identity.emailOf(user.id)), currentRole(), authentication.sessionsOf(user.id), currentSession())
    }
}
