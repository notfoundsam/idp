package idp.identity.adapters.webauthn

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.CollectedClientDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.util.exception.WebAuthnException
import com.webauthn4j.verifier.exception.MaliciousCounterValueException
import idp.identity.domain.credential.PasskeyCredential
import tools.jackson.core.JacksonException

/** RS256 stays for authenticators that offer nothing else: the system only verifies it, see V11.6.1 in `docs/security/asvs-l2.md`. */
private val ALGORITHMS =
    listOf(COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.EdDSA, COSEAlgorithmIdentifier.RS256)

internal class VerifiedRegistration(
    val credentialId: ByteArray,
    val attestedCredentialData: ByteArray,
    val signCount: Long,
    val backupEligible: Boolean,
    val backedUp: Boolean,
)

internal sealed interface AssertionCheck {
    class Passed(
        val signCount: Long,
        val backedUp: Boolean,
    ) : AssertionCheck

    data object CounterRegressed : AssertionCheck

    data object Failed : AssertionCheck
}

/**
 * The relying party is the full host of the tenant, and its origin is that host over HTTPS. A passkey therefore works on one tenant only.
 * Attestation is not evaluated: the service asks for `none`, and no decision here depends on the make of an authenticator.
 */
internal class PasskeyVerifier {
    private val objects = ObjectConverter()
    private val webAuthn = WebAuthnManager.createNonStrictWebAuthnManager(objects)
    private val attestedData = AttestedCredentialDataConverter(objects)
    private val clientData = CollectedClientDataConverter(objects)
    private val parameters = ALGORITHMS.map { PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, it) }

    val algorithms: List<Long> = ALGORITHMS.map { it.value }

    /** Read from the response before anything is verified: it only tells which stored challenge to spend. */
    fun challengeOf(clientDataJson: ByteArray): ByteArray? = orRefused(null) { clientData.convert(clientDataJson)?.challenge?.value }

    fun verifyRegistration(
        attestationObject: ByteArray,
        clientDataJson: ByteArray,
        challenge: ByteArray,
        host: String,
    ): VerifiedRegistration? =
        orRefused(null) {
            val request = RegistrationRequest(attestationObject, clientDataJson)
            val data = webAuthn.verify(request, RegistrationParameters(server(host, challenge), parameters, true, true))
            val authenticatorData = checkNotNull(data.attestationObject).authenticatorData
            val attested = checkNotNull(authenticatorData.attestedCredentialData)
            VerifiedRegistration(
                attested.credentialId,
                attestedData.convert(attested),
                authenticatorData.signCount,
                authenticatorData.isFlagBE,
                authenticatorData.isFlagBS,
            )
        }

    fun verifyAssertion(
        credential: PasskeyCredential,
        authenticatorData: ByteArray,
        clientDataJson: ByteArray,
        signature: ByteArray,
        challenge: ByteArray,
        host: String,
    ): AssertionCheck =
        orRefused(AssertionCheck.Failed) {
            try {
                val request = AuthenticationRequest(credential.credentialId, authenticatorData, clientDataJson, signature)
                val expected = AuthenticationParameters(server(host, challenge), record(credential), null, true, true)
                val data = checkNotNull(webAuthn.verify(request, expected).authenticatorData)
                AssertionCheck.Passed(data.signCount, data.isFlagBS)
            } catch (_: MaliciousCounterValueException) {
                AssertionCheck.CounterRegressed
            }
        }

    /** A parser failure comes out of WebAuthn4J as it is, not as its own exception, and anyone can send malformed bytes. */
    private inline fun <T> orRefused(
        refusal: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (_: WebAuthnException) {
            refusal
        } catch (_: JacksonException) {
            refusal
        }

    private fun server(
        host: String,
        challenge: ByteArray,
    ) = ServerProperty(Origin("https://$host"), host, DefaultChallenge(challenge))

    /** The attestation statement is not kept, and verifying a login does not read it. */
    private fun record(credential: PasskeyCredential) =
        CredentialRecordImpl(
            NoneAttestationStatement(),
            true,
            credential.backupEligible,
            credential.backedUp,
            credential.signCount,
            attestedData.convert(credential.attestedCredentialData),
            null,
            null,
            null,
            null,
        )
}
