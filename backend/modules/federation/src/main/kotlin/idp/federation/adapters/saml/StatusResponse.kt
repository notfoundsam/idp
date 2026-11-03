package idp.federation.adapters.saml

import idp.federation.application.SignatureAlgorithm
import idp.federation.application.SigningCredential
import net.shibboleth.shared.xml.SerializeSupport
import org.opensaml.core.xml.util.XMLObjectSupport
import org.opensaml.saml.common.SAMLObjectContentReference
import org.opensaml.saml.common.SAMLVersion
import org.opensaml.saml.saml2.core.Issuer
import org.opensaml.saml.saml2.core.Response
import org.opensaml.saml.saml2.core.Status
import org.opensaml.saml.saml2.core.StatusCode
import org.opensaml.security.x509.BasicX509Credential
import org.opensaml.xmlsec.signature.Signature
import org.opensaml.xmlsec.signature.support.SignatureConstants
import org.opensaml.xmlsec.signature.support.Signer
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

private const val ID_BYTES = 16

internal enum class SamlError(
    val top: String,
    val second: String,
) {
    REQUEST_DENIED(StatusCode.REQUESTER, StatusCode.REQUEST_DENIED),
    NO_AUTHN_CONTEXT(StatusCode.RESPONDER, StatusCode.NO_AUTHN_CONTEXT),
    NO_PASSIVE(StatusCode.RESPONDER, StatusCode.NO_PASSIVE),
}

/** A Response without an assertion, signed as a whole, so the SP can trust the refusal as much as a login. */
internal object StatusResponse {
    private val random = SecureRandom()

    /** Base64 of the signed XML, ready for the form. */
    fun write(
        issuer: String,
        replyTo: ReplyTo,
        error: SamlError,
        credential: SigningCredential,
        algorithm: SignatureAlgorithm,
        now: Instant,
    ): String {
        val response =
            OpenSaml.build<Response>(Response.DEFAULT_ELEMENT_NAME).apply {
                id = "_" + HexFormat.of().formatHex(ByteArray(ID_BYTES).also(random::nextBytes))
                version = SAMLVersion.VERSION_20
                issueInstant = now
                destination = replyTo.acsUrl
                inResponseTo = replyTo.requestId
                this.issuer = OpenSaml.build<Issuer>(Issuer.DEFAULT_ELEMENT_NAME).apply { value = issuer }
                status = status(error)
            }
        val signature =
            OpenSaml.build<Signature>(Signature.DEFAULT_ELEMENT_NAME).apply {
                signingCredential = BasicX509Credential(credential.certificate, credential.privateKey)
                signatureAlgorithm = algorithm.uri
                canonicalizationAlgorithm = SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS
            }
        response.signature = signature
        (signature.contentReferences.single() as SAMLObjectContentReference).digestAlgorithm = SignatureAlgorithm.DIGEST_URI
        XMLObjectSupport.marshall(response)
        Signer.signObject(signature)
        return Base64.getEncoder().encodeToString(SerializeSupport.nodeToString(response.dom!!).toByteArray())
    }

    private fun status(error: SamlError): Status =
        OpenSaml.build<Status>(Status.DEFAULT_ELEMENT_NAME).apply {
            statusCode =
                OpenSaml.build<StatusCode>(StatusCode.DEFAULT_ELEMENT_NAME).apply {
                    value = error.top
                    statusCode = OpenSaml.build<StatusCode>(StatusCode.DEFAULT_ELEMENT_NAME).apply { value = error.second }
                }
        }
}
