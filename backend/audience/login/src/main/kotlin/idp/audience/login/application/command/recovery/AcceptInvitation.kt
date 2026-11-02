package idp.audience.login.application.command.recovery

import idp.audience.login.application.command.clientIp
import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowAccess
import idp.authentication.application.FlowView
import idp.identity.application.IdentityService
import idp.identity.application.InvitationNotValid
import idp.identity.application.PasswordService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.sharedkernel.tenant.TenantResolver
import idp.sharedkernel.tenant.TenantTransaction

/** Not a data class: `toString` must not print the token or the password. [host] is where the tenant was resolved from. */
class AcceptInvitation(
    val flow: FlowAccess,
    val token: String,
    val password: String,
    val host: String,
) : Command<FlowView> {
    override val transaction = TransactionScope.NONE
}

class AcceptInvitationHandler(
    private val transaction: TenantTransaction,
    private val authentication: AuthenticationService,
    private val identity: IdentityService,
    private val passwords: PasswordService,
    private val tenants: TenantResolver,
    private val audit: AuditLog,
) : MessageHandler<AcceptInvitation, FlowView> {
    override val messageType = AcceptInvitation::class

    /** The token is spent only together with the password: a refused password must not cost the invitation. */
    override fun handle(message: AcceptInvitation): FlowView {
        authentication.claimInvitation(message.flow)
        try {
            val userId = identity.invitationOwner(message.token) ?: refuse()
            val prepared = passwords.prepare(userId, message.password, tenants.wordsOf(message.host))
            val actor = Actor.User(userId)
            val ip = clientIp()
            return transaction.atomically {
                identity.acceptInvitation(message.token, actor, ip)
                passwords.store(prepared, actor, ip)
                authentication.invitationAccepted(message.flow, userId)
            }
        } finally {
            authentication.releaseStep(message.flow)
        }
    }

    private fun refuse(): Nothing {
        audit.tokenRefused(INVITATION)
        throw InvitationNotValid()
    }
}
