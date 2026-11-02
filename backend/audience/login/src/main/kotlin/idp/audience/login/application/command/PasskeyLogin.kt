package idp.audience.login.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowAccess
import idp.authentication.application.FlowView
import idp.authentication.application.LoginFactor
import idp.identity.application.PasskeyAssertion
import idp.identity.application.PasskeyAuthenticationOptions
import idp.identity.application.PasskeyService
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope

/** Writes a row for an anonymous caller, so it takes a flow: the limit on open flows bounds what one caller adds. */
class BeginPasskeyLogin(
    val flow: FlowAccess,
) : Command<PasskeyAuthenticationOptions>

class BeginPasskeyLoginHandler(
    private val authentication: AuthenticationService,
    private val passkeys: PasskeyService,
) : MessageHandler<BeginPasskeyLogin, PasskeyAuthenticationOptions> {
    override val messageType = BeginPasskeyLogin::class

    override fun handle(message: BeginPasskeyLogin): PasskeyAuthenticationOptions {
        authentication.step(message.flow)
        return passkeys.beginAuthentication()
    }
}

/** [host] is the one the tenant was resolved from, and it is the relying party. */
class SubmitPasskey(
    val flow: FlowAccess,
    val assertion: PasskeyAssertion,
    val host: String,
) : Command<FlowView> {
    override val transaction = TransactionScope.NONE
}

class SubmitPasskeyHandler(
    private val authentication: AuthenticationService,
    private val passkeys: PasskeyService,
    private val attempts: LoginAttempts,
) : MessageHandler<SubmitPasskey, FlowView> {
    override val messageType = SubmitPasskey::class

    /** A forged response and a valid passkey of someone else answer alike, otherwise a passkey of one's own would probe logins. */
    override fun handle(message: SubmitPasskey): FlowView =
        attempts.step(message.flow, LoginFactor.PASSKEY) {
            passkeys.authenticate(message.assertion, message.host, clientIp())?.let { owner ->
                authentication.passkeyPassed(message.flow, owner)
            }
        }
}
