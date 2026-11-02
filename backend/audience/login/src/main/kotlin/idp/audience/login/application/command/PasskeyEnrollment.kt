package idp.audience.login.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowAccess
import idp.authentication.application.LoginFactor
import idp.identity.application.PasskeyAttestation
import idp.identity.application.PasskeyNotValid
import idp.identity.application.PasskeyRegistrationOptions
import idp.identity.application.PasskeyService
import idp.identity.application.TotpService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope

class BeginPasskeyEnrollment(
    val flow: FlowAccess,
) : Command<PasskeyRegistrationOptions>

class BeginPasskeyEnrollmentHandler(
    private val authentication: AuthenticationService,
    private val passkeys: PasskeyService,
) : MessageHandler<BeginPasskeyEnrollment, PasskeyRegistrationOptions> {
    override val messageType = BeginPasskeyEnrollment::class

    override fun handle(message: BeginPasskeyEnrollment): PasskeyRegistrationOptions =
        passkeys.beginRegistration(authentication.enrollingUser(message.flow, LoginFactor.PASSKEY))
}

/** [host] is the one the tenant was resolved from, and it is the relying party. */
class FinishPasskeyEnrollment(
    val flow: FlowAccess,
    val attestation: PasskeyAttestation,
    val host: String,
) : Command<EnrolledFactor> {
    override val transaction = TransactionScope.NONE
}

class FinishPasskeyEnrollmentHandler(
    private val authentication: AuthenticationService,
    private val passkeys: PasskeyService,
    private val totp: TotpService,
    private val attempts: LoginAttempts,
) : MessageHandler<FinishPasskeyEnrollment, EnrolledFactor> {
    override val messageType = FinishPasskeyEnrollment::class

    override fun handle(message: FinishPasskeyEnrollment): EnrolledFactor =
        attempts.enrollment(message.flow, LoginFactor.PASSKEY) { userId ->
            val actor = Actor.User(userId)
            try {
                passkeys.finishRegistration(userId, message.attestation, message.host, actor, clientIp())
                val codes = totp.issueRecoveryCodes(userId, actor, clientIp())
                EnrolledFactor(authentication.enrolled(message.flow, LoginFactor.PASSKEY), codes)
            } catch (_: PasskeyNotValid) {
                null
            }
        }
}
