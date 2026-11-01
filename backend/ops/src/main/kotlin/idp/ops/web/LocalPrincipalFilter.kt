package idp.ops.web

import idp.audience.operator.OperatorSecurityConfig
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.SecurityContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

const val LOCAL_OPERATOR = "local-operator"

/** Stands in for SAML until `ops` becomes a service provider. Without this filter a request has no principal and is refused. */
class LocalPrincipalFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        SecurityContext.with(PRINCIPAL) { chain.doFilter(request, response) }
    }

    private companion object {
        val PRINCIPAL = Principal(Actor.Operator(LOCAL_OPERATOR), OperatorSecurityConfig.OPERATOR)
    }
}
