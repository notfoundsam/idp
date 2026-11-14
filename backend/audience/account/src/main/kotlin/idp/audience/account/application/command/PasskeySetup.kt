package idp.audience.account.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.LoginFactor
import idp.identity.application.PasskeyAttestation
import idp.identity.application.PasskeyNotValid
import idp.identity.application.PasskeyRegistrationOptions
import idp.identity.application.PasskeyService
import idp.identity.application.TotpService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.sharedkernel.security.RequiresFreshAuthentication
import java.util.UUID

class BeginPasskeySetup :
    Command<PasskeyRegistrationOptions>,
    RequiresFreshAuthentication

class BeginPasskeySetupHandler(
    private val passkeys: PasskeyService,
) : MessageHandler<BeginPasskeySetup, PasskeyRegistrationOptions> {
    override val messageType = BeginPasskeySetup::class

    override fun handle(message: BeginPasskeySetup): PasskeyRegistrationOptions = passkeys.beginRegistration(currentUser().id)
}

/** [host] is the one the tenant was resolved from, and it is the relying party. The service spends the challenge on its own. */
class FinishPasskeySetup(
    val attestation: PasskeyAttestation,
    val host: String,
) : Command<IssuedCodes>,
    RequiresFreshAuthentication {
    override val transaction = TransactionScope.NONE
}

class FinishPasskeySetupHandler(
    private val passkeys: PasskeyService,
    private val totp: TotpService,
    private val audit: AuditLog,
) : MessageHandler<FinishPasskeySetup, IssuedCodes> {
    override val messageType = FinishPasskeySetup::class

    override fun handle(message: FinishPasskeySetup): IssuedCodes {
        val user = currentUser()
        try {
            passkeys.finishRegistration(user.id, message.attestation, message.host, user, clientIp())
        } catch (_: PasskeyNotValid) {
            audit.credentialRefused(user, LoginFactor.PASSKEY)
            throw CredentialRejected()
        }
        return totp.codesForNewFactor(user)
    }
}

class RemovePasskey(
    val id: UUID,
) : Command<Unit>,
    RequiresFreshAuthentication

class RemovePasskeyHandler(
    private val passkeys: PasskeyService,
    private val authentication: AuthenticationService,
) : MessageHandler<RemovePasskey, Unit> {
    override val messageType = RemovePasskey::class

    override fun handle(message: RemovePasskey) {
        val user = currentUser()
        passkeys.remove(user.id, message.id, user, clientIp(), authentication.factorRequirementFor(currentRole()))
    }
}
