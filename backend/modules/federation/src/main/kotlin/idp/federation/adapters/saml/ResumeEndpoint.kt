package idp.federation.adapters.saml

import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowCookie
import idp.authentication.application.SessionCookie
import idp.federation.application.FederationService
import idp.sharedkernel.web.ClientIp
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/**
 * Where a login through an SP ends. The browser comes back by an opaque URL, so everything is checked again here: the session, the
 * browser the request was bound to, the demands of the SP against this session, and the assignment. The request is spent before the
 * assignment is asked, so a Response never goes out twice for it.
 */
@RestController
class ResumeEndpoint(
    private val federation: FederationService,
    private val authentication: AuthenticationService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val replies = SamlReplies(federation, clock)

    @GetMapping("$RESUME_PATH/{id}")
    fun resume(
        @PathVariable id: UUID,
        @CookieValue(SessionCookie.NAME, required = false) token: String?,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
        response: HttpServletResponse,
    ) {
        val session = token?.let(authentication::session) ?: return replies.write(response, SamlPages.error())
        val pending = browser?.let { federation.consumeRequest(id, it) } ?: return replies.write(response, SamlPages.error())
        val request = replies.stored(pending)
        val demands = checkNotNull(Demands.of(request))
        val sp = federation.serviceProvider(pending.spId)
        if (sp == null || !demands.metBy(session, pending.acceptedAt)) return replies.write(response, SamlPages.error())
        val replyTo = ReplyTo(sp, pending.acsUrl, checkNotNull(request.id), pending.relayState)
        val subject = federation.signIn(sp.id, session.userId, ClientIp.currentOrNull()?.address)
        if (subject == null) {
            val page = if (demands.passive) replies.error(replyTo, SamlError.REQUEST_DENIED) else SamlPages.denied()
            return replies.write(response, page)
        }
        val issuer = federation.entityId() ?: return replies.write(response, SamlPages.error())
        val contextClass = AuthnContextClasses.of(Assurance.of(session.factors), request.requestedAuthnContext)
        val login = Login(issuer, replyTo, subject, session.authenticatedAt, session.expiresAt, contextClass)
        val samlResponse = ResponseBuilder.write(login, federation.signingCredential(), clock.instant())
        replies.write(response, SamlPages.post(replyTo.acsUrl, samlResponse, replyTo.relayState))
    }
}
