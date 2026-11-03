package idp.ops.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter

/**
 * `ops` answers on its own host only. The session cookie and the ACS belong to it, so a login through any other name of the same
 * service, such as the address of the load balancer, would start a session the browser never sends back where it should.
 */
class OpsHostFilter(
    private val host: String,
) : OncePerRequestFilter(),
    Ordered {
    /** After the headers and the client address, before Spring Security. */
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        if (request.serverName.equals(host, ignoreCase = true)) {
            chain.doFilter(request, response)
        } else {
            response.status = HttpServletResponse.SC_NOT_FOUND
        }
    }
}
