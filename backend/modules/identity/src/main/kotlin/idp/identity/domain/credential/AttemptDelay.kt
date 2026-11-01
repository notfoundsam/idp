package idp.identity.domain.credential

import java.time.Duration
import java.time.Instant

private const val FREE_FAILURES = 3
private const val MAX_DOUBLINGS = 10
private val FIRST_DELAY: Duration = Duration.ofSeconds(1)
private val MAX_DELAY: Duration = Duration.ofMinutes(15)

internal object AttemptDelay {
    /** The cap keeps a victim of someone else's guesses able to log in, at about 100 guesses a day for the attacker. */
    fun nextAttemptAfterFailure(
        failedAttempts: Int,
        now: Instant,
    ): Instant? {
        val extra = failedAttempts + 1 - FREE_FAILURES
        if (extra <= 0) return null
        val delay = FIRST_DELAY.multipliedBy(1L shl minOf(extra - 1, MAX_DOUBLINGS))
        return now.plus(minOf(delay, MAX_DELAY))
    }
}
