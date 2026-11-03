package idp.ops.security

import idp.sharedkernel.testing.MovableClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

class AssertionReplayCacheTest :
    FunSpec({
        val clock = MovableClock()
        val cache = AssertionReplayCache(clock)

        test("an assertion ID is accepted once until it expires, then forgotten") {
            val until = clock.instant().plus(Duration.ofMinutes(10))

            cache.firstUse("_a", until) shouldBe true
            cache.firstUse("_a", until) shouldBe false
            cache.firstUse("_b", until) shouldBe true

            clock.offset = Duration.ofMinutes(11)
            cache.firstUse("_a", clock.instant().plus(Duration.ofMinutes(10))) shouldBe true
        }

        test("an assertion without an ID is never accepted") {
            cache.firstUse(null, clock.instant().plusSeconds(60)) shouldBe false
            cache.firstUse(" ", clock.instant().plusSeconds(60)) shouldBe false
        }
    })
