package idp.sharedkernel.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter

class ClientIpFilter(
    private val resolver: ClientIpResolver,
) : OncePerRequestFilter(),
    Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        ClientIp.with(resolver.resolve(request.remoteAddr, request::getHeader)) { chain.doFilter(request, response) }
    }
}
