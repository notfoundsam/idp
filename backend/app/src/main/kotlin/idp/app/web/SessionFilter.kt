package idp.app.web

import idp.authentication.application.AuthenticationService
import idp.authentication.application.SessionCookie
import idp.sharedkernel.security.SecurityContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/** A dead session is no error: the request goes on as anonymous, and the bus decides whether that is enough. */
class SessionFilter(
    private val authentication: AuthenticationService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val token = request.cookies?.firstOrNull { it.name == SessionCookie.NAME }?.value
        val principal = token?.let(authentication::resolveSession)
        if (principal == null) {
            chain.doFilter(request, response)
        } else {
            SecurityContext.with(principal) { chain.doFilter(request, response) }
        }
    }

    companion object {
        /** Needs the tenant of the request. */
        const val ORDER = TenantFilter.ORDER + 1
    }
}
