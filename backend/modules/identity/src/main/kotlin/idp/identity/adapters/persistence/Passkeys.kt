package idp.identity.adapters.persistence

import idp.db.jooq.tenant.tables.PasskeyChallenges.PASSKEY_CHALLENGES
import idp.db.jooq.tenant.tables.PasskeyCredentials.PASSKEY_CREDENTIALS
import idp.db.jooq.tenant.tables.records.PasskeyCredentialsRecord
import idp.identity.domain.credential.PasskeyCredential
import idp.sharedkernel.scheduling.TenantCleanup
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal class Passkeys {
    /** False when the credential is already registered: an id can belong to one user only. */
    fun add(
        dsl: DSLContext,
        credential: PasskeyCredential,
    ): Boolean =
        dsl
            .insertInto(PASSKEY_CREDENTIALS)
            .set(PASSKEY_CREDENTIALS.ID, credential.id)
            .set(PASSKEY_CREDENTIALS.USER_ID, credential.userId)
            .set(PASSKEY_CREDENTIALS.CREDENTIAL_ID, credential.credentialId)
            .set(PASSKEY_CREDENTIALS.ATTESTED_CREDENTIAL_DATA, credential.attestedCredentialData)
            .set(PASSKEY_CREDENTIALS.SIGN_COUNT, credential.signCount)
            .set(PASSKEY_CREDENTIALS.BACKUP_ELIGIBLE, credential.backupEligible)
            .set(PASSKEY_CREDENTIALS.BACKED_UP, credential.backedUp)
            .set(PASSKEY_CREDENTIALS.CREATED_AT, credential.createdAt.atOffset(ZoneOffset.UTC))
            .onConflict(PASSKEY_CREDENTIALS.CREDENTIAL_ID)
            .doNothing()
            .execute() == 1

    fun find(
        dsl: DSLContext,
        credentialId: ByteArray,
    ): PasskeyCredential? =
        dsl.selectFrom(PASSKEY_CREDENTIALS).where(PASSKEY_CREDENTIALS.CREDENTIAL_ID.eq(credentialId)).fetchOne(::toCredential)

    fun credentialIds(
        dsl: DSLContext,
        userId: UUID,
    ): List<ByteArray> =
        dsl
            .select(PASSKEY_CREDENTIALS.CREDENTIAL_ID)
            .from(PASSKEY_CREDENTIALS)
            .where(PASSKEY_CREDENTIALS.USER_ID.eq(userId))
            .fetch(PASSKEY_CREDENTIALS.CREDENTIAL_ID)
            .filterNotNull()

    /** A counter of zero means the authenticator keeps none. Any other must grow, or two copies of the key are in use. */
    fun recordUse(
        dsl: DSLContext,
        credential: PasskeyCredential,
        signCount: Long,
        backedUp: Boolean,
        now: Instant,
    ): Boolean {
        val stored = PASSKEY_CREDENTIALS.SIGN_COUNT
        val allowed = if (signCount == 0L) stored.eq(0L) else stored.lt(signCount)
        return dsl
            .update(PASSKEY_CREDENTIALS)
            .set(stored, signCount)
            .set(PASSKEY_CREDENTIALS.BACKED_UP, backedUp)
            .set(PASSKEY_CREDENTIALS.LAST_USED_AT, now.atOffset(ZoneOffset.UTC))
            .where(PASSKEY_CREDENTIALS.ID.eq(credential.id), allowed)
            .execute() == 1
    }

    private fun toCredential(row: PasskeyCredentialsRecord) =
        PasskeyCredential(
            row.id!!,
            row.userId!!,
            row.credentialId!!,
            row.attestedCredentialData!!,
            row.signCount!!,
            row.backupEligible!!,
            row.backedUp!!,
            row.createdAt!!.toInstant(),
            row.lastUsedAt?.toInstant(),
        )
}

internal class PasskeyChallenges {
    fun add(
        dsl: DSLContext,
        challenge: ByteArray,
        userId: UUID?,
        expiresAt: Instant,
    ) {
        dsl
            .insertInto(PASSKEY_CHALLENGES)
            .set(PASSKEY_CHALLENGES.CHALLENGE, challenge)
            .set(PASSKEY_CHALLENGES.USER_ID, userId)
            .set(PASSKEY_CHALLENGES.EXPIRES_AT, expiresAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    /** One statement, so of two concurrent responses only one gets the row. A null [userId] means a login. */
    fun consume(
        dsl: DSLContext,
        challenge: ByteArray,
        userId: UUID?,
        now: Instant,
    ): Boolean =
        dsl
            .deleteFrom(PASSKEY_CHALLENGES)
            .where(
                PASSKEY_CHALLENGES.CHALLENGE.eq(challenge),
                PASSKEY_CHALLENGES.USER_ID.isNotDistinctFrom(userId),
                PASSKEY_CHALLENGES.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)),
            ).execute() == 1
}

internal class PasskeyChallengeCleanup : TenantCleanup {
    override val name: String = "passkey-challenges"

    override fun delete(
        dsl: DSLContext,
        now: OffsetDateTime,
    ) {
        dsl.deleteFrom(PASSKEY_CHALLENGES).where(PASSKEY_CHALLENGES.EXPIRES_AT.le(now)).execute()
    }
}
