package idp.identity.domain.credential

import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID

private const val UUID_BYTES = 16

/** The public key is no secret, so nothing here is encrypted. [attestedCredentialData] is kept as the authenticator sent it. */
internal class PasskeyCredential(
    val id: UUID,
    val userId: UUID,
    val credentialId: ByteArray,
    val attestedCredentialData: ByteArray,
    val signCount: Long,
    val backupEligible: Boolean,
    val backedUp: Boolean,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
) {
    /** A discoverable credential always sends its handle, so a missing one is a refusal. */
    fun isOwnedBy(handle: ByteArray?): Boolean = handle != null && handle.contentEquals(userHandle(userId))

    companion object {
        fun new(
            userId: UUID,
            credentialId: ByteArray,
            attestedCredentialData: ByteArray,
            signCount: Long,
            backupEligible: Boolean,
            backedUp: Boolean,
            now: Instant,
        ) = PasskeyCredential(
            UUID.randomUUID(),
            userId,
            credentialId,
            attestedCredentialData,
            signCount,
            backupEligible,
            backedUp,
            now,
            lastUsedAt = null,
        )

        /** WebAuthn forbids personal data here, and a random id has none. */
        fun userHandle(userId: UUID): ByteArray =
            ByteBuffer
                .allocate(UUID_BYTES)
                .putLong(userId.mostSignificantBits)
                .putLong(userId.leastSignificantBits)
                .array()
    }
}
