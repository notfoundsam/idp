package idp.identity.domain

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Not a data class: `toString` must not print the token. */
internal class IssuedInvitation(
    val invitation: Invitation,
    val token: OneTimeToken,
)

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
            val token = OneTimeToken.generate(random)
            return IssuedInvitation(Invitation(userId, token.hash(), now.plus(ttl)), token)
        }
    }
}
