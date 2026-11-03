package idp.audience.account.application.command

import idp.authentication.application.LoginFactor
import idp.identity.application.TotpEnrollment
import idp.identity.application.TotpService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.sharedkernel.security.RequiresFreshAuthentication

/** A factor added by a stolen session would outlive it, so every change of a factor needs a fresh login. */
class BeginTotpSetup :
    Command<TotpEnrollment>,
    RequiresFreshAuthentication

class BeginTotpSetupHandler(
    private val totp: TotpService,
) : MessageHandler<BeginTotpSetup, TotpEnrollment> {
    override val messageType = BeginTotpSetup::class

    override fun handle(message: BeginTotpSetup): TotpEnrollment = totp.enroll(currentUser().id)
}

/** Not a data class: `toString` must not print the code. Without a transaction, so a wrong code keeps the attempt it counted. */
class ConfirmTotpSetup(
    val code: String,
) : Command<IssuedCodes>,
    RequiresFreshAuthentication {
    override val transaction = TransactionScope.NONE
}

class ConfirmTotpSetupHandler(
    private val totp: TotpService,
    private val audit: AuditLog,
) : MessageHandler<ConfirmTotpSetup, IssuedCodes> {
    override val messageType = ConfirmTotpSetup::class

    override fun handle(message: ConfirmTotpSetup): IssuedCodes {
        val user = currentUser()
        if (!totp.confirm(user.id, message.code, user, clientIp())) {
            audit.credentialRefused(user, LoginFactor.TOTP)
            throw CredentialRejected()
        }
        return totp.codesForNewFactor(user)
    }
}

class RemoveTotp :
    Command<Unit>,
    RequiresFreshAuthentication

class RemoveTotpHandler(
    private val totp: TotpService,
) : MessageHandler<RemoveTotp, Unit> {
    override val messageType = RemoveTotp::class

    override fun handle(message: RemoveTotp) {
        val user = currentUser()
        totp.remove(user.id, user, clientIp())
    }
}
