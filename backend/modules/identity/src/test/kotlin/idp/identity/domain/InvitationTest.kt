package idp.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

private const val MIN_TOKEN_BYTES = 16
private val NOW: Instant = Instant.parse("2026-11-01T00:00:00Z")
private val TTL: Duration = Duration.ofHours(72)

private fun issue(random: SecureRandom = SecureRandom()) = Invitation.issue(UUID.randomUUID(), NOW, TTL, random)

class InvitationTest :
    FunSpec({
        test("the token carries at least 128 bits") {
            Base64
                .getUrlDecoder()
                .decode(issue().token.value)
                .size shouldBeGreaterThanOrEqual MIN_TOKEN_BYTES
        }

        test("the token comes from the given CSPRNG") {
            val fixed =
                object : SecureRandom() {
                    override fun nextBytes(bytes: ByteArray) = bytes.fill(7)
                }

            issue(fixed).token.value shouldBe issue(fixed).token.value
            Base64.getUrlDecoder().decode(issue(fixed).token.value).toSet() shouldBe setOf<Byte>(7)
        }

        test("the token is not a UUID") {
            shouldThrow<IllegalArgumentException> { UUID.fromString(issue().token.value) }
        }

        test("two tokens differ") {
            issue().token.value shouldNotBe issue().token.value
        }

        test("the invitation holds only the SHA-256 of the token") {
            val issued = issue()

            issued.invitation.tokenHash shouldBe MessageDigest.getInstance("SHA-256").digest(issued.token.value.toByteArray())
        }

        test("the same text hashes to the same value, so a token finds its row") {
            val issued = issue()

            InvitationToken(issued.token.value).hash() shouldBe issued.invitation.tokenHash
        }

        test("the invitation expires after the TTL") {
            issue().invitation.expiresAt shouldBe NOW.plus(TTL)
        }

        test("toString does not print the token") {
            val issued = issue()

            issued.token.toString() shouldNotContain issued.token.value
            "$issued" shouldNotContain issued.token.value
        }
    })
