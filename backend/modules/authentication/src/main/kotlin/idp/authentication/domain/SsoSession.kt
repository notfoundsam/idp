package idp.authentication.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

private const val TOKEN_BYTES = 32

internal class SessionToken(
    val value: String,
) {
    fun hash(): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    override fun toString(): String = "SessionToken(***)"

    companion object {
        fun generate(random: SecureRandom): SessionToken {
            val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            return SessionToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }
    }
}

/**
 * The token leaves in a cookie and is never stored, so a stolen database opens no session. The timeouts come from the policy of the
 * moment, so tightening it reaches sessions that are already open.
 */
internal class SsoSession(
    val id: UUID,
    val userId: UUID,
    val tokenHash: ByteArray,
    val factors: Set<AuthFactor>,
    val ipCiphertext: ByteArray?,
    val userAgentCiphertext: ByteArray?,
    val authenticatedAt: Instant,
    val lastSeenAt: Instant,
    revokedAt: Instant? = null,
    val version: Long = 0,
) {
    var revokedAt = revokedAt
        private set

    fun isLive(
        policy: TenantPolicy,
        now: Instant,
    ): Boolean =
        revokedAt == null &&
            now.isBefore(lastSeenAt.plus(policy.sessionIdleTimeout)) &&
            now.isBefore(authenticatedAt.plus(policy.sessionAbsoluteTimeout))

    /** Skipped activity makes the idle timeout end early by at most the interval, never late. */
    fun isDueForLastSeen(
        now: Instant,
        interval: Duration,
    ): Boolean = !now.isBefore(lastSeenAt.plus(interval))

    fun revoke(now: Instant) {
        revokedAt = now
    }
}
