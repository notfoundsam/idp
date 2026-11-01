package idp.identity.domain.credential

import org.bouncycastle.util.encoders.Base32
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val SECRET_BYTES = 20
private const val STEP_SECONDS = 30L
private const val TOLERANCE_STEPS = 1L
private const val DIGITS = 6
private const val CODE_MODULUS = 1_000_000
private const val OFFSET_MASK = 0x0f
private const val POSITIVE_MASK = 0x7fffffff

/** The only use of SHA-1 in the system: RFC 6238 authenticator apps implement nothing else. See V11.4.1 in `docs/security/asvs-l2.md`. */
private const val HMAC_ALGORITHM = "HmacSHA1"

/** Not a data class: `toString` must not print the secret. */
internal class TotpSecret(
    val bytes: ByteArray,
) {
    fun encoded(): String = Base32.toBase32String(bytes)

    fun code(step: Long): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM).apply { init(SecretKeySpec(bytes, HMAC_ALGORITHM)) }
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        val offset = hash.last().toInt() and OFFSET_MASK
        val value = ByteBuffer.wrap(hash, offset, Int.SIZE_BYTES).int and POSITIVE_MASK
        return (value % CODE_MODULUS).toString().padStart(DIGITS, '0')
    }

    /** Whether the step was used already is not its business: only the conditional update can answer that under concurrency. */
    fun matchedStep(
        code: String,
        now: Instant,
    ): Long? {
        val current = stepAt(now)
        val sent = code.toByteArray()
        return (current + TOLERANCE_STEPS downTo current - TOLERANCE_STEPS).firstOrNull { step ->
            MessageDigest.isEqual(code(step).toByteArray(), sent)
        }
    }

    override fun toString(): String = "TotpSecret(***)"

    companion object {
        fun generate(random: SecureRandom) = TotpSecret(ByteArray(SECRET_BYTES).also(random::nextBytes))

        fun stepAt(moment: Instant): Long = moment.epochSecond / STEP_SECONDS
    }
}

/** Without [confirmedAt] nobody has proven that an authenticator holds the secret, so it counts for nothing. Attempts live outside [version]. */
internal class TotpCredential(
    val userId: UUID,
    val secretCiphertext: ByteArray,
    val confirmedAt: Instant?,
    val lastUsedStep: Long?,
    val failedAttempts: Int,
    val nextAttemptAt: Instant?,
    val version: Long,
) {
    val isConfirmed: Boolean get() = confirmedAt != null

    fun canAttemptAt(now: Instant): Boolean = nextAttemptAt == null || !now.isBefore(nextAttemptAt)

    fun nextAttemptAfterFailure(now: Instant): Instant? = AttemptDelay.nextAttemptAfterFailure(failedAttempts, now)
}
