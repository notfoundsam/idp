package idp.federation.adapters.saml

import idp.federation.application.ServiceProviderView
import idp.federation.application.VerifiedRequest
import net.shibboleth.shared.xml.XMLParserException
import org.opensaml.core.xml.io.UnmarshallingException
import org.opensaml.saml.common.SAMLVersion
import org.opensaml.saml.common.xml.SAMLConstants
import org.opensaml.saml.saml2.core.AuthnRequest
import org.opensaml.saml.saml2.core.NameIDType
import org.opensaml.saml.security.impl.SAMLSignatureProfileValidator
import org.opensaml.security.SecurityException
import org.opensaml.security.x509.BasicX509Credential
import org.opensaml.xmlsec.crypto.XMLSigningUtil
import org.opensaml.xmlsec.signature.Signature
import org.opensaml.xmlsec.signature.impl.SignatureImpl
import org.opensaml.xmlsec.signature.support.SignatureConstants
import org.opensaml.xmlsec.signature.support.SignatureException
import org.opensaml.xmlsec.signature.support.SignatureValidator
import org.xml.sax.SAXException
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** A request is a few kilobytes. The limit holds before parsing, so a small deflated bomb never reaches the parser. */
const val MAX_REQUEST_BYTES = 32 * 1024

/** SAML bindings 3.4.3 asks for 80 bytes, which SPs that carry a return URL exceed. A kilobyte still keeps it out of the way. */
const val MAX_RELAY_STATE_BYTES = 1024

internal const val SAML_REQUEST = "SAMLRequest"
internal const val RELAY_STATE = "RelayState"
internal const val SIG_ALG = "SigAlg"
internal const val SIGNATURE = "Signature"

private val PARAMETERS = listOf(SAML_REQUEST, RELAY_STATE, SIG_ALG, SIGNATURE)

private val SIGNATURE_ALGORITHMS =
    setOf(
        SignatureConstants.ALGO_ID_SIGNATURE_RSASSA_PSS_SHA256_MGF1,
        SignatureConstants.ALGO_ID_SIGNATURE_RSASSA_PSS_SHA384_MGF1,
        SignatureConstants.ALGO_ID_SIGNATURE_RSASSA_PSS_SHA512_MGF1,
        SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256,
        SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA384,
        SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA512,
        SignatureConstants.ALGO_ID_SIGNATURE_ECDSA_SHA256,
        SignatureConstants.ALGO_ID_SIGNATURE_ECDSA_SHA384,
        SignatureConstants.ALGO_ID_SIGNATURE_ECDSA_SHA512,
    )

private val DIGEST_ALGORITHMS =
    setOf(SignatureConstants.ALGO_ID_DIGEST_SHA256, SignatureConstants.ALGO_ID_DIGEST_SHA384, SignatureConstants.ALGO_ID_DIGEST_SHA512)

private val WHITESPACE = Regex("\\s")

/** How a request arrived. Only its own source is read, so a parameter cannot come from the URL and the body at once. */
internal sealed interface SsoMessage {
    /** The raw query string: the signature covers its octets as the SP sent them. */
    class Redirect(
        val query: String,
    ) : SsoMessage

    /** The form fields, decoded once by the container. The container adds those of [query] to them, so [query] must name none. */
    class Post(
        val parameters: Map<String, List<String>>,
        val query: String? = null,
    ) : SsoMessage
}

/** The log names the reason and never the content. */
internal enum class Rejection {
    MALFORMED,
    REPEATED_PARAMETER,
    TOO_LARGE,
    RELAY_STATE_TOO_LONG,
    UNKNOWN_SP,
    SIGNATURE_MISSING,
    SIGNATURE_INVALID,
    ACS_NOT_REGISTERED,
    DESTINATION_MISMATCH,
    EXPIRED,
    REPLAYED,
    NO_AUTHN_CONTEXT,
    INVALID_NAME_ID_POLICY,
    SUBJECT_REQUESTED,
}

/** Where an error may go: the SP, its signature when it signs, and the ACS were all checked. */
internal class ReplyTo(
    val sp: ServiceProviderView,
    val acsUrl: String,
    val requestId: String,
    val relayState: String?,
)

internal sealed interface Verdict {
    class Accepted(
        val request: VerifiedRequest,
        val replyTo: ReplyTo,
    ) : Verdict

    class Refused(
        val rejection: Rejection,
        val replyTo: ReplyTo? = null,
    ) : Verdict
}

private class Refusal(
    val rejection: Rejection,
    val replyTo: ReplyTo? = null,
) : RuntimeException(rejection.name, null, false, false)

/** One parameter of a source, with its raw form kept for the signature of the Redirect binding. */
private class Parameter(
    val raw: String,
    val value: String,
)

/**
 * Checks an AuthnRequest before anything of it is used. The Issuer is read first only to find the key, and nothing else of the
 * request counts until the signature holds. [lifetime] and [skew] bound IssueInstant, and they decide how long an ID is kept.
 */
internal class AuthnRequestValidator(
    private val serviceProviders: (String) -> ServiceProviderView?,
    private val clock: Clock = Clock.systemUTC(),
    private val lifetime: Duration = Duration.ofMinutes(3),
    private val skew: Duration = Duration.ofMinutes(3),
) {
    fun validate(
        message: SsoMessage,
        destination: String,
    ): Verdict =
        try {
            verify(message, destination)
        } catch (refusal: Refusal) {
            Verdict.Refused(refusal.rejection, refusal.replyTo)
        }

    private fun verify(
        message: SsoMessage,
        destination: String,
    ): Verdict {
        val parameters = parameters(message)
        val relayState = parameters[RELAY_STATE]?.value
        if (relayState != null && relayState.toByteArray().size > MAX_RELAY_STATE_BYTES) refuse(Rejection.RELAY_STATE_TOO_LONG)
        val encoded = parameters[SAML_REQUEST]?.value ?: refuse(Rejection.MALFORMED)
        val xml =
            when (message) {
                is SsoMessage.Redirect -> inflate(base64(encoded))
                is SsoMessage.Post ->
                    base64(encoded.replace(WHITESPACE, "")).also {
                        if (it.size >
                            MAX_REQUEST_BYTES
                        ) {
                            refuse(Rejection.TOO_LARGE)
                        }
                    }
            }
        val request = read(xml)
        val sp = request.issuer?.value?.let(serviceProviders) ?: refuse(Rejection.UNKNOWN_SP)
        val signed =
            when (message) {
                is SsoMessage.Redirect -> verifyRedirect(request, parameters, sp)
                is SsoMessage.Post -> request.signature?.let { verifyEnveloped(it, sp) } ?: false
            }
        if (sp.authnRequestsSigned && !signed) refuse(Rejection.SIGNATURE_MISSING)
        val replyTo = ReplyTo(sp, acsOf(request, sp), request.id ?: refuse(Rejection.MALFORMED), relayState)
        if (request.version != SAMLVersion.VERSION_20) refuse(Rejection.MALFORMED, replyTo)
        if ((signed && request.destination == null) || (request.destination != null && request.destination != destination)) {
            refuse(Rejection.DESTINATION_MISMATCH, replyTo)
        }
        val issued = request.issueInstant ?: refuse(Rejection.MALFORMED, replyTo)
        val now = clock.instant()
        if (issued.isBefore(now - lifetime - skew) || issued.isAfter(now + skew)) refuse(Rejection.EXPIRED, replyTo)
        Demands.of(request) ?: refuse(Rejection.NO_AUTHN_CONTEXT, replyTo)
        if (request.subject != null) refuse(Rejection.SUBJECT_REQUESTED, replyTo)
        val format = request.nameIDPolicy?.format
        if (format != null && format != NameIDType.UNSPECIFIED && format != sp.settings.nameIdFormat.uri) {
            refuse(Rejection.INVALID_NAME_ID_POLICY, replyTo)
        }
        val verified = VerifiedRequest(sp.id, replyTo.requestId, replyTo.acsUrl, xml, relayState, issued + lifetime + skew)
        return Verdict.Accepted(verified, replyTo)
    }

    /** Names are compared decoded, so an encoded name cannot slip a second copy past the check. */
    private fun parameters(message: SsoMessage): Map<String, Parameter> {
        val all =
            when (message) {
                is SsoMessage.Redirect -> query(message.query)
                is SsoMessage.Post -> {
                    if (query(message.query.orEmpty()).any { (name) -> name in PARAMETERS }) refuse(Rejection.REPEATED_PARAMETER)
                    message.parameters.flatMap { (name, values) -> values.map { name to Parameter(it, it) } }
                }
            }
        val known = all.filter { (name) -> name in PARAMETERS }
        if (known.groupBy({ it.first }).any { (_, copies) -> copies.size > 1 }) refuse(Rejection.REPEATED_PARAMETER)
        return known.toMap()
    }

    private fun query(query: String): List<Pair<String, Parameter>> =
        query
            .split('&')
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .map { (name, raw) -> decoded(name) to Parameter(raw, decoded(raw)) }

    private fun decoded(raw: String): String =
        try {
            URLDecoder.decode(raw, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            refuse(Rejection.MALFORMED)
        }

    private fun base64(value: String): ByteArray =
        try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            refuse(Rejection.MALFORMED)
        }

    /** Reads one byte past the limit, which is enough to know the limit was passed. */
    private fun inflate(deflated: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(deflated)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(MAX_REQUEST_BYTES + 1)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer, 0, MAX_REQUEST_BYTES + 1 - out.size())
                out.write(buffer, 0, read)
                if (out.size() > MAX_REQUEST_BYTES) refuse(Rejection.TOO_LARGE)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) refuse(Rejection.MALFORMED)
            }
            return out.toByteArray()
        } catch (_: DataFormatException) {
            refuse(Rejection.MALFORMED)
        } finally {
            inflater.end()
        }
    }

    private fun read(xml: ByteArray): AuthnRequest =
        try {
            val document = OpenSaml.parse(xml)
            OpenSaml.validate(document)
            OpenSaml.unmarshall(document.documentElement) as? AuthnRequest ?: refuse(Rejection.MALFORMED)
        } catch (_: XMLParserException) {
            refuse(Rejection.MALFORMED)
        } catch (_: SAXException) {
            refuse(Rejection.MALFORMED)
        } catch (_: UnmarshallingException) {
            refuse(Rejection.MALFORMED)
        }

    /** SAML bindings 3.4.4.1: the signature covers the parameters in this order, as sent, and the XML carries none of its own. */
    private fun verifyRedirect(
        request: AuthnRequest,
        parameters: Map<String, Parameter>,
        sp: ServiceProviderView,
    ): Boolean {
        val signature = parameters[SIGNATURE] ?: return if (parameters.containsKey(SIG_ALG)) refuse(Rejection.MALFORMED) else false
        if (request.signature != null) refuse(Rejection.SIGNATURE_INVALID)
        val algorithm = parameters[SIG_ALG]?.value?.takeIf { it in SIGNATURE_ALGORITHMS } ?: refuse(Rejection.SIGNATURE_INVALID)
        val octets =
            listOfNotNull(SAML_REQUEST, RELAY_STATE.takeIf { parameters.containsKey(it) }, SIG_ALG)
                .joinToString("&") { "$it=${parameters.getValue(it).raw}" }
                .toByteArray()
        val bytes = base64(signature.value)
        val valid =
            sp.signingCertificates.any { certificate ->
                try {
                    XMLSigningUtil.verifyWithURI(BasicX509Credential(certificate), algorithm, bytes, octets)
                } catch (_: SecurityException) {
                    false
                }
            }
        return valid || refuse(Rejection.SIGNATURE_INVALID)
    }

    /**
     * The profile check makes the one reference point at the root, by its ID, with only the enveloped and exclusive C14N transforms.
     * The schema has already refused a document that repeats an ID. Together they leave no room for a wrapped copy.
     */
    private fun verifyEnveloped(
        signature: Signature,
        sp: ServiceProviderView,
    ): Boolean {
        try {
            SAMLSignatureProfileValidator().validate(signature)
        } catch (_: SignatureException) {
            refuse(Rejection.SIGNATURE_INVALID)
        }
        val signedInfo = (signature as SignatureImpl).xmlSignature?.signedInfo ?: refuse(Rejection.SIGNATURE_INVALID)
        val digest = signedInfo.item(0).messageDigestAlgorithm.algorithmURI
        if (signature.signatureAlgorithm !in SIGNATURE_ALGORITHMS || digest !in DIGEST_ALGORITHMS) refuse(Rejection.SIGNATURE_INVALID)
        val valid =
            sp.signingCertificates.any { certificate ->
                try {
                    SignatureValidator.validate(signature, BasicX509Credential(certificate))
                    true
                } catch (_: SignatureException) {
                    false
                }
            }
        return valid || refuse(Rejection.SIGNATURE_INVALID)
    }

    /** SAML core 3.4.1: an index or a URL with an optional binding, never both. A URL counts only as registered, to the character. */
    private fun acsOf(
        request: AuthnRequest,
        sp: ServiceProviderView,
    ): String {
        val index = request.assertionConsumerServiceIndex
        val url = request.assertionConsumerServiceURL
        val binding = request.protocolBinding
        if (binding != null && binding != SAMLConstants.SAML2_POST_BINDING_URI) refuse(Rejection.ACS_NOT_REGISTERED)
        val endpoint =
            when {
                index != null && url != null -> refuse(Rejection.ACS_NOT_REGISTERED)
                index != null -> sp.acs.firstOrNull { it.index == index }
                url != null -> sp.acs.firstOrNull { it.url == url }
                else -> sp.acs.firstOrNull { it.isDefault }
            }
        return endpoint?.url ?: refuse(Rejection.ACS_NOT_REGISTERED)
    }

    private fun refuse(
        rejection: Rejection,
        replyTo: ReplyTo? = null,
    ): Nothing = throw Refusal(rejection, replyTo)
}
