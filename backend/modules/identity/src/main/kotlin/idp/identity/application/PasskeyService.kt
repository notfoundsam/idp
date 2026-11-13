package idp.identity.application

import idp.identity.adapters.persistence.PasskeyChallenges
import idp.identity.adapters.persistence.Passkeys
import idp.identity.adapters.persistence.RecoveryCodes
import idp.identity.adapters.persistence.Totps
import idp.identity.adapters.persistence.Users
import idp.identity.adapters.webauthn.AssertionCheck
import idp.identity.adapters.webauthn.PasskeyVerifier
import idp.identity.domain.credential.PasskeyCredential
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.domain.DomainRuleViolation
import idp.sharedkernel.domain.NotFound
import idp.sharedkernel.tenant.TenantTransaction
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val PASSKEY_REGISTERED = "user.passkey_registered"
private const val PASSKEY_COUNTER_REGRESSED = "user.passkey_counter_regressed"
private const val PASSKEY_REMOVED = "user.passkey_removed"
private const val PASSKEY = "passkey"

private const val CHALLENGE_BYTES = 32

/** The same answer for every refusal: what exactly failed helps only someone forging a response. */
class PasskeyNotValid : DomainRuleViolation("The passkey is not valid")

class PasskeyNotFound(
    id: UUID,
) : NotFound("No passkey $id")

class PasskeyView(
    val id: UUID,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val backedUp: Boolean,
)

class PasskeyRegistrationOptions(
    val challenge: ByteArray,
    val userHandle: ByteArray,
    val algorithms: List<Long>,
    val excludeCredentials: List<ByteArray>,
)

class PasskeyAuthenticationOptions(
    val challenge: ByteArray,
)

class PasskeyAttestation(
    val attestationObject: ByteArray,
    val clientDataJson: ByteArray,
)

class PasskeyAssertion(
    val credentialId: ByteArray,
    val userHandle: ByteArray?,
    val authenticatorData: ByteArray,
    val clientDataJson: ByteArray,
    val signature: ByteArray,
)

/** What [PasskeyService] verifies, so the options of both audiences ask the browser for the same. */
object PasskeyCeremony {
    const val RESIDENT_KEY = "required"
    const val USER_VERIFICATION = "required"
    const val ATTESTATION = "none"
}

/**
 * Passkeys are discoverable, so a login starts before anyone is known. The caller passes the host the tenant was resolved from, it
 * is the relying party. The browser must get the options of [PasskeyCeremony].
 */
class PasskeyService(
    private val transaction: TenantTransaction,
    private val audit: AuditLog,
    private val clock: Clock = Clock.systemUTC(),
    private val challengeTtl: Duration = Duration.ofMinutes(5),
) {
    private val users = Users()
    private val passkeys = Passkeys()
    private val recoveryCodes = RecoveryCodes()
    private val challenges = PasskeyChallenges()
    private val floor = FactorFloor(users, Totps(), passkeys)
    private val verifier = PasskeyVerifier()
    private val random = SecureRandom()

    fun beginRegistration(userId: UUID): PasskeyRegistrationOptions =
        transaction.execute { dsl ->
            users.find(dsl, userId) ?: throw UserNotFound(userId)
            val challenge = newChallenge()
            challenges.add(dsl, challenge, userId, clock.instant().plus(challengeTtl))
            PasskeyRegistrationOptions(
                challenge,
                PasskeyCredential.userHandle(userId),
                verifier.algorithms,
                passkeys.credentialIds(dsl, userId),
            )
        }

    /** The challenge is spent in its own transaction: a refusal rolls back, and it must not make the challenge usable again. */
    fun finishRegistration(
        userId: UUID,
        attestation: PasskeyAttestation,
        host: String,
        actor: Actor,
        ip: String? = null,
    ): UUID {
        val challenge = verifier.challengeOf(attestation.clientDataJson) ?: throw PasskeyNotValid()
        val now = clock.instant()
        if (!transaction.execute { dsl -> challenges.consume(dsl, challenge, userId, now) }) throw PasskeyNotValid()
        val verified =
            verifier.verifyRegistration(attestation.attestationObject, attestation.clientDataJson, challenge, host)
                ?: throw PasskeyNotValid()
        val credential =
            PasskeyCredential.new(
                userId,
                verified.credentialId,
                verified.attestedCredentialData,
                verified.signCount,
                verified.backupEligible,
                verified.backedUp,
                now,
            )
        transaction.execute { dsl ->
            if (!passkeys.add(dsl, credential)) throw PasskeyNotValid()
            audit.recordChange(AuditRecord(actor, PASSKEY_REGISTERED, AuditTarget.user(userId), ip))
        }
        return credential.id
    }

    fun hasPasskey(userId: UUID): Boolean = transaction.execute { dsl -> passkeys.credentialIds(dsl, userId).isNotEmpty() }

    /** The oldest first. */
    fun passkeysOf(userId: UUID): List<PasskeyView> =
        transaction.execute { dsl ->
            passkeys.ofUser(dsl, userId).map { PasskeyView(it.id, it.createdAt, it.lastUsedAt, it.backedUp) }
        }

    /**
     * A passkey of someone else reads as missing. Recovery codes go when this was the last second factor. [requirement] refuses a
     * removal that would leave the user below it.
     */
    fun remove(
        userId: UUID,
        passkeyId: UUID,
        actor: Actor,
        ip: String?,
        requirement: FactorRequirement,
    ) {
        transaction.execute { dsl ->
            floor.removing(dsl, userId, requirement) {
                if (!passkeys.delete(dsl, userId, passkeyId)) throw PasskeyNotFound(passkeyId)
            }
            val codesRemoved = recoveryCodes.deleteWithoutSecondFactor(dsl, userId)
            val details = mapOf(PASSKEY to passkeyId.toString()) + removedCodes(codesRemoved)
            audit.recordChange(AuditRecord(actor, PASSKEY_REMOVED, AuditTarget.user(userId), ip, details))
        }
    }

    fun beginAuthentication(): PasskeyAuthenticationOptions =
        transaction.execute { dsl ->
            val challenge = newChallenge()
            challenges.add(dsl, challenge, null, clock.instant().plus(challengeTtl))
            PasskeyAuthenticationOptions(challenge)
        }

    /** The owner of the passkey, or null for any refusal. The login handler audits the outcome and checks the status of the user. */
    fun authenticate(
        assertion: PasskeyAssertion,
        host: String,
        ip: String? = null,
    ): UUID? {
        val challenge = verifier.challengeOf(assertion.clientDataJson) ?: return null
        return transaction.execute { dsl ->
            val now = clock.instant()
            if (!challenges.consume(dsl, challenge, null, now)) return@execute null
            val credential = passkeys.find(dsl, assertion.credentialId) ?: return@execute null
            if (!credential.isOwnedBy(assertion.userHandle)) return@execute null
            val check =
                verifier.verifyAssertion(
                    credential,
                    assertion.authenticatorData,
                    assertion.clientDataJson,
                    assertion.signature,
                    challenge,
                    host,
                )
            val recorded =
                when (check) {
                    is AssertionCheck.Passed -> passkeys.recordUse(dsl, credential, check.signCount, check.backedUp, now)
                    AssertionCheck.CounterRegressed -> false
                    AssertionCheck.Failed -> return@execute null
                }
            if (recorded) return@execute credential.userId
            audit.recordDenial(AuditRecord(Actor.Anonymous, PASSKEY_COUNTER_REGRESSED, AuditTarget.user(credential.userId), ip))
            null
        }
    }

    private fun newChallenge() = ByteArray(CHALLENGE_BYTES).also(random::nextBytes)
}
