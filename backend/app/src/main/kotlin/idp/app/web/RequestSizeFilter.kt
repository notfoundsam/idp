package idp.app.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter

/**
 * The API has no large body, and a browser sends a length with each one. Refusing chunked bodies keeps that length the whole story.
 * The paths in [uploads] take a file and have a limit of their own, matched exactly, so any other spelling gets the small one.
 */
class RequestSizeFilter(
    private val maxBytes: Long,
    private val uploads: Map<String, Long> = emptyMap(),
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        when {
            request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null -> response.status = HttpServletResponse.SC_LENGTH_REQUIRED
            request.contentLengthLong > (uploads[request.requestURI] ?: maxBytes) ->
                response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
            else -> chain.doFilter(request, response)
        }
    }

    companion object {
        const val ORDER = ClientVersionFilter.ORDER + 1
    }
}
