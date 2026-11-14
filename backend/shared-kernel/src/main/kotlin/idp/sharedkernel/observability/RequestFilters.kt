package idp.sharedkernel.observability

import idp.sharedkernel.outbox.EventCause
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** Inside the observation of Spring, so its lines carry the trace, and around everything else of the request. */
private const val CORRELATION_ORDER = Ordered.HIGHEST_PRECEDENCE + 2

/** A request is one correlation: its lines and the events it publishes carry the same ID, whatever the client sends. */
class CorrelationFilter :
    OncePerRequestFilter(),
    Ordered {
    override fun getOrder(): Int = CORRELATION_ORDER

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        EventCause.with(EventCause(UUID.randomUUID(), null)) { chain.doFilter(request, response) }
    }
}

/**
 * Catches what a handler or a filter throws before Tomcat logs it with its text. The tenant scope has ended by then, so the
 * tenant filter leaves the ID in [TENANT_ATTRIBUTE]. The answer is the error page of Spring, which says nothing of the cause.
 */
class UnexpectedErrorFilter(
    private val failures: Failures,
) : OncePerRequestFilter(),
    Ordered {
    override fun getOrder(): Int = ORDER

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        try {
            chain.doFilter(request, response)
        } catch (failure: Exception) {
            val cause = (failure as? ServletException)?.cause ?: failure
            val tenant = request.getAttribute(TENANT_ATTRIBUTE) as String?
            if (tenant == null) {
                failures.record(FailureSource.HTTP, cause)
            } else {
                LogFields.with(LogFields.TENANT_ID, tenant) { failures.record(FailureSource.HTTP, cause) }
            }
            if (!response.isCommitted) response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        }
    }

    companion object {
        const val ORDER = CORRELATION_ORDER + 1

        const val TENANT_ATTRIBUTE = "idp.observability.tenant_id"
    }
}
