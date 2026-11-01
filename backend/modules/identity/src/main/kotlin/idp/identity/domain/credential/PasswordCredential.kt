package idp.identity.domain.credential

import java.time.Instant
import java.util.UUID

/** Attempts live outside [version]: they change on every failure, and that must not conflict with a change of the password. */
internal class PasswordCredential(
    val userId: UUID,
    val hash: String,
    val failedAttempts: Int,
    val nextAttemptAt: Instant?,
    val version: Long,
) {
    fun canAttemptAt(now: Instant): Boolean = nextAttemptAt == null || !now.isBefore(nextAttemptAt)

    fun nextAttemptAfterFailure(now: Instant): Instant? = AttemptDelay.nextAttemptAfterFailure(failedAttempts, now)

    companion object {
        fun new(
            userId: UUID,
            hash: String,
        ) = PasswordCredential(userId, hash, failedAttempts = 0, nextAttemptAt = null, version = 0)
    }
}
