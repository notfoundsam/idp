package archfixtures.throttleonlyinapp.ops

import archfixtures.throttleonlyinapp.sharedkernel.throttle.RateLimiter

class Violation(
    val limiter: RateLimiter,
)
