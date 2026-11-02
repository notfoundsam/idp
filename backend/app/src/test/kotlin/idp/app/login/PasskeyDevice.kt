package idp.app.login

import com.webauthn4j.data.AttestationConveyancePreference
import com.webauthn4j.data.AuthenticatorSelectionCriteria
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.PublicKeyCredentialRpEntity
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.PublicKeyCredentialUserEntity
import com.webauthn4j.data.ResidentKeyRequirement
import com.webauthn4j.data.UserVerificationRequirement
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor
import com.webauthn4j.test.client.ClientPlatform
import tools.jackson.databind.JsonNode
import java.util.Base64

private fun encoded(bytes: ByteArray?): String? = bytes?.let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

private fun decoded(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

private fun json(vararg fields: Pair<String, String?>) =
    fields.joinToString(",", "{", "}") { (name, value) -> "\"$name\":${value?.let { "\"$it\"" } ?: "null"}" }

/** An authenticator that answers what the login page would hand it, in the JSON the API takes. */
class PasskeyDevice(
    private val host: String = ACME,
) {
    private val origin = Origin("https://$host")
    private val client = ClientPlatform(origin, WebAuthnAuthenticatorAdaptor(NoneAttestationAuthenticator()))

    fun attestation(options: JsonNode): String {
        val credential =
            client.create(
                PublicKeyCredentialCreationOptions(
                    PublicKeyCredentialRpEntity(options.text("rpId"), "Acme"),
                    PublicKeyCredentialUserEntity(decoded(options.text("userHandle")), EMAIL, "Ada"),
                    DefaultChallenge(decoded(options.text("challenge"))),
                    options.get("algorithms").values().map {
                        PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.create(it.asLong()))
                    },
                    null,
                    null,
                    AuthenticatorSelectionCriteria(null, ResidentKeyRequirement.REQUIRED, UserVerificationRequirement.REQUIRED),
                    AttestationConveyancePreference.NONE,
                    null,
                ),
            )
        val response = checkNotNull(credential.response)
        return json("attestationObject" to encoded(response.attestationObject), "clientDataJson" to encoded(response.clientDataJSON))
    }

    fun assertion(options: JsonNode): String {
        val required = UserVerificationRequirement.REQUIRED
        val challenge = DefaultChallenge(decoded(options.text("challenge")))
        val credential = client.get(PublicKeyCredentialRequestOptions(challenge, null, options.text("rpId"), null, required, null))
        val response = checkNotNull(credential.response)
        return json(
            "credentialId" to encoded(credential.rawId),
            "userHandle" to encoded(response.userHandle),
            "authenticatorData" to encoded(response.authenticatorData),
            "clientDataJson" to encoded(response.clientDataJSON),
            "signature" to encoded(response.signature),
        )
    }
}
