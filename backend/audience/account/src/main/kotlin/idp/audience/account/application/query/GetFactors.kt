package idp.audience.account.application.query

import idp.identity.application.PasskeyService
import idp.identity.application.PasskeyView
import idp.identity.application.TotpService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.Query
import idp.sharedkernel.security.SecurityContext

class Factors(
    val totp: Boolean,
    val passkeys: List<PasskeyView>,
    val recoveryCodesLeft: Int,
)

/** Names nobody: the user is the one of the session. */
class GetFactors : Query<Factors>

class GetFactorsHandler(
    private val totp: TotpService,
    private val passkeys: PasskeyService,
) : MessageHandler<GetFactors, Factors> {
    override val messageType = GetFactors::class

    override fun handle(message: GetFactors): Factors {
        val user = checkNotNull(SecurityContext.currentOrNull()).actor as Actor.User
        return Factors(totp.hasConfirmedTotp(user.id), passkeys.passkeysOf(user.id), totp.recoveryCodesLeft(user.id))
    }
}
