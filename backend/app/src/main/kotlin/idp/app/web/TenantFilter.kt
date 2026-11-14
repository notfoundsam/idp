package idp.app.web

import idp.sharedkernel.observability.LogFields
import idp.sharedkernel.observability.UnexpectedErrorFilter
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantResolver
import idp.sharedkernel.tenant.TenantStatus
import io.micrometer.common.KeyValue
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.filter.ServerHttpObservationFilter

/** Only ACTIVE passes. Other statuses set the code without an error page, so nothing reveals what the host is. */
class TenantFilter(
    private val tenants: TenantResolver,
) : OncePerRequestFilter(),
    Ordered {
    override fun getOrder(): Int = ORDER

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val tenant = tenants.resolve(request.serverName)
        when (tenant?.status) {
            TenantStatus.ACTIVE -> {
                val context = tenant.context()
                // A high cardinality value goes to the span only, so the metrics keep one series per endpoint.
                ServerHttpObservationFilter.findObservationContext(request).ifPresent {
                    it.addHighCardinalityKeyValue(KeyValue.of(TENANT_ID, context.tenantId.toString()))
                }
                request.setAttribute(UnexpectedErrorFilter.TENANT_ATTRIBUTE, context.tenantId.toString())
                TenantContext.with(context) { chain.doFilter(request, response) }
            }
            TenantStatus.SUSPENDED, TenantStatus.OFFBOARDING -> response.status = HttpServletResponse.SC_FORBIDDEN
            else -> response.status = HttpServletResponse.SC_NOT_FOUND
        }
    }

    companion object {
        /** After the checks that need no tenant, so an outdated client or an oversized request costs no lookup. */
        const val ORDER = RequestSizeFilter.ORDER + 1

        const val TENANT_ID = LogFields.TENANT_ID
    }
}
