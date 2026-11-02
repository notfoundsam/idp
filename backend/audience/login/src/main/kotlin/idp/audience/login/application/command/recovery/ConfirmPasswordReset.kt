package idp.audience.login.application.command.recovery

import idp.audience.login.application.command.clientIp
import idp.authentication.application.AuthenticationService
import idp.identity.application.IdentityService
import idp.identity.application.PasswordResetNotValid
import idp.identity.application.PasswordService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.sharedkernel.tenant.TenantResolver
import idp.sharedkernel.tenant.TenantTransaction

/** Not a data class: `toString` must not print the token or the password. [host] is where the tenant was resolved from. */
class ConfirmPasswordReset(
    val token: String,
    val password: String,
    val host: String,
) : Command<Unit> {
    override val transaction = TransactionScope.NONE
}

class ConfirmPasswordResetHandler(
    private val transaction: TenantTransaction,
    private val authentication: AuthenticationService,
    private val identity: IdentityService,
    private val passwords: PasswordService,
    private val tenants: TenantResolver,
    private val audit: AuditLog,
) : MessageHandler<ConfirmPasswordReset, Unit> {
    override val messageType = ConfirmPasswordReset::class

    /**
     * The hash comes first, with no connection held. The token, the password and the sessions then change in one transaction, so no
     * old session outlives the new password. No session opens here: the next login asks for every factor.
     */
    override fun handle(message: ConfirmPasswordReset) {
        val userId = identity.passwordResetOwner(message.token) ?: refuse()
        val prepared = passwords.prepare(userId, message.password, tenants.wordsOf(message.host))
        val actor = Actor.User(userId)
        val ip = clientIp()
        transaction.atomically {
            identity.completePasswordReset(message.token, ip)
            passwords.store(prepared, actor, ip)
            authentication.revokeUserSessions(userId, actor, ip)
        }
    }

    private fun refuse(): Nothing {
        audit.tokenRefused(PASSWORD_RESET)
        throw PasswordResetNotValid()
    }
}
