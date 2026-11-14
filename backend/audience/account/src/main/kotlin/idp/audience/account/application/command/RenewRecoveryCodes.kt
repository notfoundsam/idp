package idp.audience.account.application.command

import idp.identity.application.PasskeyService
import idp.identity.application.TotpService
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.security.RequiresFreshAuthentication

/** The codes are a way into the account, so a stolen session must not be able to read new ones. The old ones stop working. */
class RenewRecoveryCodes :
    Command<IssuedCodes>,
    RequiresFreshAuthentication

class RenewRecoveryCodesHandler(
    private val totp: TotpService,
    private val passkeys: PasskeyService,
) : MessageHandler<RenewRecoveryCodes, IssuedCodes> {
    override val messageType = RenewRecoveryCodes::class

    override fun handle(message: RenewRecoveryCodes): IssuedCodes {
        val user = currentUser()
        if (!totp.hasConfirmedTotp(user.id) && !passkeys.hasPasskey(user.id)) throw NoSecondFactor()
        return IssuedCodes(totp.issueRecoveryCodes(user.id, user, clientIp()))
    }
}
