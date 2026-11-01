package idp.identity.domain.credential

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.bouncycastle.util.encoders.Base32
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

private val RFC_SECRET = "12345678901234567890".toByteArray()
private val MOMENT: Instant = Instant.parse("2026-11-01T00:00:45Z")
private val USER = UUID.randomUUID()

private fun credential(
    failedAttempts: Int = 0,
    nextAttemptAt: Instant? = null,
    confirmedAt: Instant? = MOMENT,
) = TotpCredential(USER, ByteArray(0), confirmedAt, null, failedAttempts, nextAttemptAt, 0)

class TotpCredentialTest :
    FunSpec({
        context("the code of RFC 6238 appendix B, cut to six digits") {
            listOf(
                59L to "287082",
                1111111109L to "081804",
                1111111111L to "050471",
                1234567890L to "005924",
                2000000000L to "279037",
                20000000000L to "353130",
            ).forEach { (seconds, code) ->
                test("$seconds gives $code") {
                    TotpSecret(RFC_SECRET).code(TotpSecret.stepAt(Instant.ofEpochSecond(seconds))) shouldBe code
                }
            }
        }

        context("the step") {
            test("lasts 30 seconds") {
                TotpSecret.stepAt(Instant.ofEpochSecond(0)) shouldBe 0L
                TotpSecret.stepAt(Instant.ofEpochSecond(29)) shouldBe 0L
                TotpSecret.stepAt(Instant.ofEpochSecond(30)) shouldBe 1L
                TotpSecret.stepAt(Instant.ofEpochSecond(59)) shouldBe 1L
                TotpSecret.stepAt(Instant.ofEpochSecond(60)) shouldBe 2L
            }
        }

        context("the tolerance") {
            val secret = TotpSecret(RFC_SECRET)
            val current = TotpSecret.stepAt(MOMENT)

            test("accepts the current step") {
                secret.matchedStep(secret.code(current), MOMENT) shouldBe current
            }

            test("accepts one step back") {
                secret.matchedStep(secret.code(current - 1), MOMENT) shouldBe current - 1
            }

            test("accepts one step ahead") {
                secret.matchedStep(secret.code(current + 1), MOMENT) shouldBe current + 1
            }

            test("refuses two steps back") {
                secret.matchedStep(secret.code(current - 2), MOMENT).shouldBeNull()
            }

            test("refuses two steps ahead") {
                secret.matchedStep(secret.code(current + 2), MOMENT).shouldBeNull()
            }

            test("refuses anything that is not the code") {
                secret.matchedStep("", MOMENT).shouldBeNull()
                secret.matchedStep(secret.code(current) + "0", MOMENT).shouldBeNull()
            }
        }

        context("a new secret") {
            test("carries 160 bits from the given CSPRNG") {
                val requested = mutableListOf<Int>()
                val random =
                    object : SecureRandom() {
                        override fun nextBytes(bytes: ByteArray) {
                            requested += bytes.size
                            super.nextBytes(bytes)
                        }
                    }

                val secret = TotpSecret.generate(random)

                requested shouldBe listOf(20)
                secret.bytes shouldHaveSize 20
            }

            test("is encoded the way authenticator apps read it") {
                val secret = TotpSecret(RFC_SECRET)

                secret.encoded() shouldMatch Regex("[A-Z2-7]{32}")
                Base32.decode(secret.encoded()) shouldBe RFC_SECRET
            }

            test("stays out of toString") {
                val secret = TotpSecret(RFC_SECRET)

                secret.toString() shouldNotContain secret.encoded()
            }
        }

        context("the state of a credential") {
            test("a credential without confirmation is not confirmed") {
                credential(confirmedAt = null).isConfirmed shouldBe false
                credential().isConfirmed shouldBe true
            }

            test("the first failures bring no delay") {
                credential(failedAttempts = 2).nextAttemptAfterFailure(MOMENT).shouldBeNull()
            }

            test("the next failure starts the delay of a password") {
                credential(failedAttempts = 3).nextAttemptAfterFailure(MOMENT) shouldBe MOMENT.plusSeconds(1)
            }

            test("no attempt runs until the delay ends") {
                val credential = credential(nextAttemptAt = MOMENT.plusSeconds(1))

                credential.canAttemptAt(MOMENT) shouldBe false
                credential.canAttemptAt(MOMENT.plusSeconds(1)) shouldBe true
            }
        }
    })
