package idp.sharedkernel.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter

private const val ONE_YEAR_SECONDS = 31_536_000
private const val API_PREFIX = "/api/"

/** Comes first, so a refusal from any later filter carries the headers too. The API serves no markup, so its policy stays closed. */
class SecurityHeadersFilter :
    OncePerRequestFilter(),
    Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        response.setHeader("Strict-Transport-Security", "max-age=$ONE_YEAR_SECONDS; includeSubDomains")
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Content-Security-Policy", "default-src 'none'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
        if (request.requestURI.startsWith(API_PREFIX)) response.setHeader("Cache-Control", "no-store")
        chain.doFilter(request, response)
    }
}
