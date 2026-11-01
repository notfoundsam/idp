package idp.identity.domain.credential

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val NOW: Instant = Instant.parse("2026-11-01T00:00:00Z")

private fun credential(
    failedAttempts: Int = 0,
    nextAttemptAt: Instant? = null,
) = PasswordCredential(UUID.randomUUID(), "hash", failedAttempts, nextAttemptAt, version = 0)

private fun delayAfter(failures: Int): Duration? =
    credential(failedAttempts = failures - 1).nextAttemptAfterFailure(NOW)?.let { Duration.between(NOW, it) }

class PasswordCredentialTest :
    FunSpec({
        test("a new credential starts without attempts") {
            val credential = PasswordCredential.new(UUID.randomUUID(), "hash")

            credential.failedAttempts shouldBe 0
            credential.nextAttemptAt.shouldBeNull()
            credential.version shouldBe 0
        }

        test("the first failures cost nothing, so a typo does not slow anyone down") {
            (1..3).forEach { delayAfter(it).shouldBeNull() }
        }

        test("the delay doubles after that") {
            (4..7).map { delayAfter(it) } shouldBe listOf(1L, 2L, 4L, 8L).map(Duration::ofSeconds)
        }

        test("the delay stops growing at 15 minutes, so a victim is never locked out for good") {
            delayAfter(13) shouldBe Duration.ofSeconds(512)
            delayAfter(14) shouldBe Duration.ofMinutes(15)
            delayAfter(1_000_000) shouldBe Duration.ofMinutes(15)
        }

        test("an attempt waits for the end of the delay") {
            val credential = credential(failedAttempts = 5, nextAttemptAt = NOW)

            credential.canAttemptAt(NOW.minusMillis(1)) shouldBe false
            credential.canAttemptAt(NOW) shouldBe true
            credential(failedAttempts = 1).canAttemptAt(NOW) shouldBe true
        }
    })
