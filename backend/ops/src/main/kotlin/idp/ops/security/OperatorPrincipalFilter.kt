package idp.ops.security

import idp.sharedkernel.security.SecurityContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock

/**
 * Hands the operator of a live SAML session to the bus. A session past the end the IdP stated is dropped here, so the request goes
 * on without a principal and the bus refuses it like any anonymous one.
 */
class OperatorPrincipalFilter(
    private val clock: Clock = Clock.systemUTC(),
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication as? OperatorAuthentication
        when {
            authentication == null -> chain.doFilter(request, response)
            clock.instant().isBefore(authentication.expiresAt) ->
                SecurityContext.with(authentication.principal) { chain.doFilter(request, response) }
            else -> {
                SecurityContextHolder.clearContext()
                request.getSession(false)?.invalidate()
                chain.doFilter(request, response)
            }
        }
    }
}
