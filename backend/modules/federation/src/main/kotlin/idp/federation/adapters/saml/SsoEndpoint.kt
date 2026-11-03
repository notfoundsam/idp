package idp.federation.adapters.saml

import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowCookie
import idp.authentication.application.LoginStep
import idp.authentication.application.SessionCookie
import idp.federation.application.FederationService
import idp.federation.application.RequestReplayed
import idp.sharedkernel.tenant.TenantContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

const val SSO_PATH = "/saml/sso"
const val RESUME_PATH = "/saml/resume"
const val LOGIN_PAGE = "/login/"

private const val SECRET_BYTES = 32

private val securityEvents = LoggerFactory.getLogger("idp.security")

/**
 * Takes an AuthnRequest in two steps. The first checks and keeps it, then sends the browser on to this origin. The second runs there,
 * where the cookies of the session and of the flows come along, and starts the login. A cross-site arrival carries neither: `Lax`
 * stays off a POST and `Strict` off any of them, and a flow started without the browser's secret would cut off its other tabs.
 */
@RestController
class SsoEndpoint(
    private val federation: FederationService,
    private val authentication: AuthenticationService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val validator = AuthnRequestValidator(federation::serviceProviderByEntityId, clock)
    private val replies = SamlReplies(federation, clock)
    private val random = SecureRandom()

    @GetMapping(SSO_PATH)
    fun redirect(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) = write(response, receive(SsoMessage.Redirect(request.queryString.orEmpty()), request))

    @PostMapping(SSO_PATH)
    fun post(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) = write(response, receive(SsoMessage.Post(request.parameterMap.mapValues { it.value.toList() }, request.queryString), request))

    /** The session is read here and nowhere before. A request ends with this step: a second visit finds nothing to claim. */
    @GetMapping("$SSO_PATH/{id}")
    fun proceed(
        @PathVariable id: UUID,
        @CookieValue(SessionCookie.NAME, required = false) session: String?,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
        response: HttpServletResponse,
    ) {
        val secret = browser ?: newSecret()
        val pending = federation.claimRequest(id, secret) ?: return write(response, SamlPages.error())
        val request = replies.stored(pending)
        val demands = checkNotNull(Demands.of(request))
        val access = authentication.startFlow(demands.continuation("$RESUME_PATH/$id"), session, secret)
        response.addHeader(HttpHeaders.SET_COOKIE, FlowCookie.issue(access.browserSecret))
        if (demands.passive && authentication.step(access).step != LoginStep.COMPLETE) {
            federation.discardRequest(id)
            val sp = federation.serviceProvider(pending.spId) ?: return write(response, SamlPages.error())
            return write(
                response,
                replies.error(ReplyTo(sp, pending.acsUrl, checkNotNull(request.id), pending.relayState), SamlError.NO_PASSIVE),
            )
        }
        response.status = HttpServletResponse.SC_SEE_OTHER
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader(HttpHeaders.LOCATION, "$LOGIN_PAGE?flow=${access.id}")
    }

    private fun receive(
        message: SsoMessage,
        request: HttpServletRequest,
    ): SamlPage {
        val verdict = validator.validate(message, "https://${request.serverName}$SSO_PATH")
        if (verdict is Verdict.Refused) return refuse(verdict.rejection, verdict.replyTo)
        val accepted = verdict as Verdict.Accepted
        val id =
            try {
                federation.acceptRequest(accepted.request)
            } catch (_: RequestReplayed) {
                return refuse(Rejection.REPLAYED, accepted.replyTo)
            }
        return SamlPages.bounce("$SSO_PATH/$id")
    }

    private fun refuse(
        rejection: Rejection,
        replyTo: ReplyTo?,
    ): SamlPage {
        securityEvents.warn("event=saml.request_rejected reason={} tenant={}", rejection, TenantContext.current().tenantId)
        val error =
            when (rejection) {
                Rejection.NO_AUTHN_CONTEXT -> SamlError.NO_AUTHN_CONTEXT
                Rejection.INVALID_NAME_ID_POLICY -> SamlError.INVALID_NAME_ID_POLICY
                Rejection.SUBJECT_REQUESTED -> SamlError.REQUEST_UNSUPPORTED
                else -> SamlError.REQUEST_DENIED
            }
        return replyTo?.let { replies.error(it, error) } ?: SamlPages.error()
    }

    private fun newSecret(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(SECRET_BYTES).also(random::nextBytes))

    private fun write(
        response: HttpServletResponse,
        page: SamlPage,
    ) = replies.write(response, page)
}
