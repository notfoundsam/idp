package idp.sharedkernel.web

import java.net.InetAddress

data class ClientIp(
    val address: String,
) {
    companion object {
        private val current: ScopedValue<ClientIp> = ScopedValue.newInstance()

        fun currentOrNull(): ClientIp? = if (current.isBound) current.get() else null

        fun <T> with(
            ip: ClientIp,
            block: () -> T,
        ): T = ScopedValue.where(current, ip).call(ScopedValue.CallableOp<T, Exception> { block() })
    }
}

/**
 * The header must be one the edge overwrites: `X-Forwarded-For` keeps what the client sent, so nothing reads it here.
 * Its value is `address:port`, as in `CloudFront-Viewer-Address`, and an IPv6 address may come without brackets.
 */
class ClientIpResolver(
    private val trustedHeader: String?,
) {
    fun resolve(
        peer: String,
        header: (String) -> String?,
    ): ClientIp {
        val viewer = trustedHeader?.let(header)?.let(::addressOf)
        return ClientIp(viewer ?: peer)
    }

    private fun addressOf(value: String): String? =
        try {
            val address = value.substringBeforeLast(':', "").removePrefix("[").removeSuffix("]")
            InetAddress.ofLiteral(address).hostAddress
        } catch (_: IllegalArgumentException) {
            null
        }
}
