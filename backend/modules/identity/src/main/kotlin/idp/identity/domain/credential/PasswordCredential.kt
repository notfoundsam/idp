package idp.identity.domain.credential

import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val FREE_FAILURES = 3
private const val MAX_DOUBLINGS = 10
private val FIRST_DELAY: Duration = Duration.ofSeconds(1)
private val MAX_DELAY: Duration = Duration.ofMinutes(15)

/** Attempts live outside [version]: they change on every failure, and that must not conflict with a change of the password. */
internal class PasswordCredential(
    val userId: UUID,
    val hash: String,
    val failedAttempts: Int,
    val nextAttemptAt: Instant?,
    val version: Long,
) {
    fun canAttemptAt(now: Instant): Boolean = nextAttemptAt == null || !now.isBefore(nextAttemptAt)

    /** The cap keeps a victim of someone else's guesses able to log in, at about 100 guesses a day for the attacker. */
    fun nextAttemptAfterFailure(now: Instant): Instant? {
        val extra = failedAttempts + 1 - FREE_FAILURES
        if (extra <= 0) return null
        val delay = FIRST_DELAY.multipliedBy(1L shl minOf(extra - 1, MAX_DOUBLINGS))
        return now.plus(minOf(delay, MAX_DELAY))
    }

    companion object {
        fun new(
            userId: UUID,
            hash: String,
        ) = PasswordCredential(userId, hash, failedAttempts = 0, nextAttemptAt = null, version = 0)
    }
}
