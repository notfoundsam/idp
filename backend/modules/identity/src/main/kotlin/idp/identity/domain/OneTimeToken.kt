package idp.identity.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private const val TOKEN_BYTES = 32

/** The value leaves in a mail and only its hash is stored, so a stolen database opens nothing. */
internal class OneTimeToken(
    val value: String,
) {
    fun hash(): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    override fun toString(): String = "OneTimeToken(***)"

    companion object {
        fun generate(random: SecureRandom): OneTimeToken {
            val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            return OneTimeToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }
    }
}
