package idp.federation.adapters.saml

import idp.federation.application.FederationService
import idp.federation.application.PendingRequest
import jakarta.servlet.http.HttpServletResponse
import org.opensaml.saml.saml2.core.AuthnRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import java.time.Clock

private val HTML = MediaType("text", "html", Charsets.UTF_8).toString()

/** What the SSO and resume endpoints share: how a page leaves, and how an error reaches a checked ACS. */
internal class SamlReplies(
    private val federation: FederationService,
    private val clock: Clock,
) {
    fun error(
        replyTo: ReplyTo,
        error: SamlError,
    ): SamlPage {
        val issuer = federation.entityId() ?: return SamlPages.error()
        val credential = federation.signingCredential()
        val response = StatusResponse.write(issuer, replyTo, error, credential, replyTo.sp.settings.signatureAlgorithm, clock.instant())
        return SamlPages.post(replyTo.acsUrl, response, replyTo.relayState)
    }

    /** Checked when it was taken, so only the fields are read. */
    fun stored(pending: PendingRequest): AuthnRequest = OpenSaml.unmarshall(OpenSaml.parse(pending.xml).documentElement) as AuthnRequest

    /** Sets the policy itself, so a page with a form replaces the closed default instead of adding a second policy to it. */
    fun write(
        response: HttpServletResponse,
        page: SamlPage,
    ) {
        response.status = page.status
        response.contentType = HTML
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        page.csp?.let { response.setHeader("Content-Security-Policy", it) }
        response.writer.write(page.html)
    }
}
