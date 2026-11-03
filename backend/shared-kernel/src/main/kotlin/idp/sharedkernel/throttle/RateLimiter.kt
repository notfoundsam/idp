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

/** For an environment whose every request comes from one address, such as the e2e tests. The limits keep their periods. */
class ScaledRateLimiter(
    private val limiter: RateLimiter,
    private val factor: Long,
) : RateLimiter,
    AutoCloseable {
    init {
        require(factor >= 1) { "A throttle multiplier below 1 is refused: $factor" }
    }

    override fun acquire(
        key: String,
        limit: ThrottleLimit,
    ): RateDecision = limiter.acquire(key, ThrottleLimit(Math.multiplyExact(limit.capacity, factor), limit.period))

    override fun close() {
        (limiter as? AutoCloseable)?.close()
    }
}
