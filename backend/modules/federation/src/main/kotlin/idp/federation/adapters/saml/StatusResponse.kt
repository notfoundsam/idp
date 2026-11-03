package idp.federation.adapters.saml

import idp.federation.application.SignatureAlgorithm
import idp.federation.application.SigningCredential
import org.opensaml.saml.common.SAMLVersion
import org.opensaml.saml.saml2.core.Issuer
import org.opensaml.saml.saml2.core.Response
import org.opensaml.saml.saml2.core.Status
import org.opensaml.saml.saml2.core.StatusCode
import java.time.Instant

internal enum class SamlError(
    val top: String,
    val second: String,
) {
    REQUEST_DENIED(StatusCode.REQUESTER, StatusCode.REQUEST_DENIED),
    NO_AUTHN_CONTEXT(StatusCode.RESPONDER, StatusCode.NO_AUTHN_CONTEXT),
    INVALID_NAME_ID_POLICY(StatusCode.REQUESTER, StatusCode.INVALID_NAMEID_POLICY),
    REQUEST_UNSUPPORTED(StatusCode.REQUESTER, StatusCode.REQUEST_UNSUPPORTED),
    NO_PASSIVE(StatusCode.RESPONDER, StatusCode.NO_PASSIVE),
}

/** The frame of every Response: addressed to the checked ACS, in response to one request. */
internal fun response(
    issuer: String,
    replyTo: ReplyTo,
    status: Status,
    now: Instant,
): Response =
    OpenSaml.build<Response>(Response.DEFAULT_ELEMENT_NAME).apply {
        id = samlId()
        version = SAMLVersion.VERSION_20
        issueInstant = now
        destination = replyTo.acsUrl
        inResponseTo = replyTo.requestId
        this.issuer = issuer(issuer)
        this.status = status
    }

internal fun issuer(value: String): Issuer = OpenSaml.build<Issuer>(Issuer.DEFAULT_ELEMENT_NAME).apply { this.value = value }

internal fun status(
    top: String,
    second: String? = null,
): Status =
    OpenSaml.build<Status>(Status.DEFAULT_ELEMENT_NAME).apply {
        statusCode =
            OpenSaml.build<StatusCode>(StatusCode.DEFAULT_ELEMENT_NAME).apply {
                value = top
                second?.let { statusCode = OpenSaml.build<StatusCode>(StatusCode.DEFAULT_ELEMENT_NAME).apply { value = it } }
            }
    }

/** A Response without an assertion, signed as a whole, so the SP can trust the refusal as much as a login. */
internal object StatusResponse {
    /** Base64 of the signed XML, ready for the form. */
    fun write(
        issuer: String,
        replyTo: ReplyTo,
        error: SamlError,
        credential: SigningCredential,
        algorithm: SignatureAlgorithm,
        now: Instant,
    ): String {
        val response = response(issuer, replyTo, status(error.top, error.second), now)
        return signedBase64(response, listOf(response), credential, algorithm)
    }
}
