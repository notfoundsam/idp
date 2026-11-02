package idp.app.web

import idp.authentication.application.CsrfCookie
import idp.authentication.application.SessionCookie
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.web.ClientIp
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

private const val CSRF_REJECTED = "access.csrf_rejected"
private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")

/**
 * Guards what a live session may do. A browser without one has nothing to lose to a forged request: a flow needs its id, which another
 * site cannot read, and a cookie sent only to this site. So a dead session cookie never stands between a user and the login.
 */
class CsrfFilter(
    private val audit: AuditLog,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val principal = SecurityContext.currentOrNull()
        val token = request.cookies?.firstOrNull { it.name == SessionCookie.NAME }?.value
        if (principal == null || token == null || request.method in SAFE_METHODS) {
            chain.doFilter(request, response)
        } else if (CsrfCookie.matches(token, request.getHeader(CsrfCookie.HEADER))) {
            chain.doFilter(request, response)
        } else {
            audit.recordDenial(AuditRecord(principal.actor, CSRF_REJECTED, ip = ClientIp.currentOrNull()?.address))
            response.status = HttpServletResponse.SC_FORBIDDEN
        }
    }

    companion object {
        /** Needs the principal of the request. */
        const val ORDER = SessionFilter.ORDER + 1
    }
}
