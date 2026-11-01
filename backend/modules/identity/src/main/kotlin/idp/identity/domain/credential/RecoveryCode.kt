package idp.identity.domain.credential

import org.bouncycastle.util.encoders.Base32
import java.security.MessageDigest
import java.security.SecureRandom

private const val CODES = 10
private const val CODE_BYTES = 16
private const val GROUP_LENGTH = 4
private const val SEPARATOR = "-"
private const val PADDING = '='

/** Not a data class: `toString` must not print the code. */
internal class RecoveryCode(
    val value: String,
) {
    /** 128 random bits need neither a salt nor a slow hash: nothing can be guessed offline. */
    fun hash(): ByteArray = MessageDigest.getInstance("SHA-256").digest(normalized().toByteArray())

    private fun normalized(): String = value.replace(SEPARATOR, "").filterNot(Char::isWhitespace).uppercase()

    override fun toString(): String = "RecoveryCode(***)"

    companion object {
        fun generate(random: SecureRandom): List<RecoveryCode> =
            List(CODES) { RecoveryCode(format(ByteArray(CODE_BYTES).also(random::nextBytes))) }

        private fun format(bytes: ByteArray): String =
            Base32
                .toBase32String(bytes)
                .trimEnd(PADDING)
                .chunked(GROUP_LENGTH)
                .joinToString(SEPARATOR)
    }
}
