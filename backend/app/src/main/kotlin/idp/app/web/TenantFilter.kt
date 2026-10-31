package idp.app.web

import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantResolver
import idp.sharedkernel.tenant.TenantStatus
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/** Only ACTIVE passes. Other statuses set the code without an error page, so nothing reveals what the host is. */
class TenantFilter(
    private val tenants: TenantResolver,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val tenant = tenants.resolve(request.serverName)
        when (tenant?.status) {
            TenantStatus.ACTIVE -> TenantContext.with(tenant.context()) { chain.doFilter(request, response) }
            TenantStatus.SUSPENDED, TenantStatus.OFFBOARDING -> response.status = HttpServletResponse.SC_FORBIDDEN
            else -> response.status = HttpServletResponse.SC_NOT_FOUND
        }
    }
}
