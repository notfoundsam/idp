package idp.authentication.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
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

private val MOMENT: Instant = Instant.parse("2026-11-01T12:00:00Z")
private val IDLE: Duration = Duration.ofMinutes(30)
private val ABSOLUTE: Duration = Duration.ofHours(12)
private val POLICY = TenantPolicy(sessionIdleTimeout = IDLE, sessionAbsoluteTimeout = ABSOLUTE)
private const val MIN_TOKEN_BYTES = 16
private const val TOKENS = 100

private fun session(
    authenticatedAt: Instant = MOMENT,
    lastSeenAt: Instant = MOMENT,
) = SsoSession(
    id = UUID.randomUUID(),
    userId = UUID.randomUUID(),
    tokenHash = ByteArray(0),
    factors = setOf(AuthFactor.PASSWORD),
    ipCiphertext = null,
    userAgentCiphertext = null,
    authenticatedAt = authenticatedAt,
    lastSeenAt = lastSeenAt,
)

class SsoSessionTest :
    FunSpec({
        test("a session lives until the idle timeout") {
            session().isLive(POLICY, MOMENT.plus(IDLE).minusMillis(1)) shouldBe true
            session().isLive(POLICY, MOMENT.plus(IDLE)) shouldBe false
        }

        test("activity moves the idle timeout") {
            val seen = MOMENT.plus(IDLE)

            session(lastSeenAt = seen).isLive(POLICY, seen.plus(IDLE).minusMillis(1)) shouldBe true
        }

        test("no activity moves the absolute timeout") {
            val end = MOMENT.plus(ABSOLUTE)

            session(lastSeenAt = end).isLive(POLICY, end.minusMillis(1)) shouldBe true
            session(lastSeenAt = end).isLive(POLICY, end) shouldBe false
        }

        test("a revoked session is not live") {
            val session = session()

            session.revoke(MOMENT)

            session.isLive(POLICY, MOMENT) shouldBe false
        }

        test("a session is due for a new last seen only after the interval") {
            val interval = Duration.ofMinutes(1)

            session().isDueForLastSeen(MOMENT.plus(interval).minusMillis(1), interval) shouldBe false
            session().isDueForLastSeen(MOMENT.plus(interval), interval) shouldBe true
        }

        test("a token carries at least 128 bits and is no UUID") {
            val token = SessionToken.generate(SecureRandom()).value

            Base64.getUrlDecoder().decode(token).size shouldBeGreaterThanOrEqual MIN_TOKEN_BYTES
            runCatching { UUID.fromString(token) }.isFailure shouldBe true
        }

        test("every token is new") {
            val random = SecureRandom()

            List(TOKENS) { SessionToken.generate(random).value }.toSet() shouldHaveSize TOKENS
        }

        test("a token is reduced to its SHA-256") {
            val token = SessionToken.generate(SecureRandom())

            token.hash() shouldBe MessageDigest.getInstance("SHA-256").digest(token.value.toByteArray())
            token.hash() shouldNotBe token.value.toByteArray()
        }

        test("a token does not print itself") {
            val token = SessionToken.generate(SecureRandom())

            token.toString() shouldNotContain token.value
        }
    })
