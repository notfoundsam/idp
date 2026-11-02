package idp.app.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter

private const val APP_VERSION_HEADER = "X-App-Version"
private const val CLIENT_VERSION_HEADER = "X-Client-Version"
private const val UPGRADE_REQUIRED = 426

private fun partsOf(version: String): List<Int>? =
    version.split('.').map { it.toIntOrNull() ?: return null }.takeIf { parts -> parts.all { it >= 0 } }

private fun isOlder(
    version: List<Int>,
    than: List<Int>,
): Boolean {
    val compared = version.zip(than).firstOrNull { (mine, theirs) -> mine != theirs }
    return compared?.let { (mine, theirs) -> mine < theirs } ?: (version.size < than.size)
}

/**
 * Old frontends stay served until a contract change needs them gone, and then the minimum goes up by hand. A client that names no
 * version is no frontend of ours, so only one that names an old or unreadable version is told to upgrade.
 */
class ClientVersionFilter(
    private val appVersion: String,
    minClientVersion: String,
) : OncePerRequestFilter() {
    private val minimum = minClientVersion.takeIf { it.isNotBlank() }?.let { checkNotNull(partsOf(it)) { "Unreadable minimum: $it" } }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        response.setHeader(APP_VERSION_HEADER, appVersion)
        val client = request.getHeader(CLIENT_VERSION_HEADER)
        if (minimum != null && client != null && isOutdated(client, minimum)) {
            response.status = UPGRADE_REQUIRED
        } else {
            chain.doFilter(request, response)
        }
    }

    private fun isOutdated(
        client: String,
        minimum: List<Int>,
    ): Boolean = partsOf(client)?.let { isOlder(it, minimum) } ?: true

    companion object {
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 1
    }
}
