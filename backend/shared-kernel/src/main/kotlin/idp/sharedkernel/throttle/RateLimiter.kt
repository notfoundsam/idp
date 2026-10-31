package idp.sharedkernel.throttle

import java.time.Duration

sealed interface RateDecision {
    data object Allowed : RateDecision

    data class Rejected(
        val retryAfter: Duration,
    ) : RateDecision
}

class TooManyRequestsException(
    val retryAfter: Duration,
) : RuntimeException("Too many requests")

interface RateLimiter {
    fun acquire(
        key: String,
        limit: ThrottleLimit,
    ): RateDecision
}
