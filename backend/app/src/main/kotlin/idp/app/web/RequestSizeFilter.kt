package idp.app.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter

/** The API has no large body, and a browser sends a length with each one. Refusing chunked bodies keeps that length the whole story. */
class RequestSizeFilter(
    private val maxBytes: Long,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        when {
            request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null -> response.status = HttpServletResponse.SC_LENGTH_REQUIRED
            request.contentLengthLong > maxBytes -> response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
            else -> chain.doFilter(request, response)
        }
    }

    companion object {
        const val ORDER = ClientVersionFilter.ORDER + 1
    }
}
