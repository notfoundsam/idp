package idp.audience.account.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.LoginFactor
import idp.identity.application.PasswordService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.sharedkernel.tenant.TenantResolver
import idp.sharedkernel.tenant.TenantTransaction

/**
 * Not a data class: `toString` must not print the passwords. The current password stands in for a fresh login, so a stolen session
 * alone cannot take the account over. [host] is the one the tenant was resolved from, its words may not be part of the password.
 * [endOtherSessions] ends the other sessions in the same step, because ending them later would need a fresh login.
 */
class ChangePassword(
    val currentPassword: String,
    val newPassword: String,
    val host: String,
    val endOtherSessions: Boolean = false,
) : Command<Unit> {
    override val transaction = TransactionScope.NONE
}

/** A wrong current password spends an attempt like a wrong password at login, and the same delay follows. */
class ChangePasswordHandler(
    private val transaction: TenantTransaction,
    private val passwords: PasswordService,
    private val authentication: AuthenticationService,
    private val tenants: TenantResolver,
    private val audit: AuditLog,
) : MessageHandler<ChangePassword, Unit> {
    override val messageType = ChangePassword::class

    /** The hash comes first, with no connection held. The password and the other sessions then change together. */
    override fun handle(message: ChangePassword) {
        val user = currentUser()
        if (!passwords.verify(user.id, message.currentPassword)) {
            audit.credentialRefused(user, LoginFactor.PASSWORD)
            throw CredentialRejected()
        }
        val words = setOfNotNull(tenants.resolve(message.host)?.name, message.host.substringBefore('.'))
        val prepared = passwords.prepare(user.id, message.newPassword, words)
        val ip = clientIp()
        transaction.atomically {
            passwords.store(prepared, user, ip)
            if (message.endOtherSessions) authentication.revokeUserSessions(user.id, user, ip, kept = currentSession())
        }
    }
}
