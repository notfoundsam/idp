package idp.identity.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

private const val TOKEN_BYTES = 32

internal class InvitationToken(
    val value: String,
) {
    fun hash(): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    override fun toString(): String = "InvitationToken(***)"
}

/** Not a data class: `toString` must not print the token. */
internal class IssuedInvitation(
    val invitation: Invitation,
    val token: InvitationToken,
)

/** The token itself leaves in the mail and is never stored, so a stolen database opens no invitation. */
internal class Invitation(
    val userId: UUID,
    val tokenHash: ByteArray,
    val expiresAt: Instant,
) {
    companion object {
        fun issue(
            userId: UUID,
            now: Instant,
            ttl: Duration,
            random: SecureRandom,
        ): IssuedInvitation {
            val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            val token = InvitationToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
            return IssuedInvitation(Invitation(userId, token.hash(), now.plus(ttl)), token)
        }
    }
}
