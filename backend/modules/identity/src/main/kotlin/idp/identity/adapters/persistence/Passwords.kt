package idp.identity.adapters.persistence

import idp.db.jooq.tenant.tables.PasswordCredentials.PASSWORD_CREDENTIALS
import idp.db.jooq.tenant.tables.records.PasswordCredentialsRecord
import idp.identity.domain.credential.PasswordCredential
import org.jooq.DSLContext
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class Passwords {
    fun find(
        dsl: DSLContext,
        userId: UUID,
    ): PasswordCredential? = dsl.selectFrom(PASSWORD_CREDENTIALS).where(PASSWORD_CREDENTIALS.USER_ID.eq(userId)).fetchOne(::toCredential)

    /** A new password clears the attempts: they counted guesses at the old one. */
    fun replace(
        dsl: DSLContext,
        credential: PasswordCredential,
    ) {
        dsl
            .insertInto(PASSWORD_CREDENTIALS)
            .set(PASSWORD_CREDENTIALS.USER_ID, credential.userId)
            .set(PASSWORD_CREDENTIALS.HASH, credential.hash)
            .onConflict(PASSWORD_CREDENTIALS.USER_ID)
            .doUpdate()
            .set(PASSWORD_CREDENTIALS.HASH, credential.hash)
            .set(PASSWORD_CREDENTIALS.FAILED_ATTEMPTS, 0)
            .setNull(PASSWORD_CREDENTIALS.NEXT_ATTEMPT_AT)
            .set(PASSWORD_CREDENTIALS.VERSION, PASSWORD_CREDENTIALS.VERSION.plus(1))
            .execute()
    }

    /** One statement, so of two concurrent attempts only one goes on, and a parallel burst cannot outrun the delay. */
    fun claimAttempt(
        dsl: DSLContext,
        credential: PasswordCredential,
        now: Instant,
    ): Boolean =
        dsl
            .update(PASSWORD_CREDENTIALS)
            .set(PASSWORD_CREDENTIALS.FAILED_ATTEMPTS, PASSWORD_CREDENTIALS.FAILED_ATTEMPTS.plus(1))
            .set(PASSWORD_CREDENTIALS.NEXT_ATTEMPT_AT, credential.nextAttemptAfterFailure(now)?.atOffset(ZoneOffset.UTC))
            .where(
                PASSWORD_CREDENTIALS.USER_ID.eq(credential.userId),
                PASSWORD_CREDENTIALS.FAILED_ATTEMPTS.eq(credential.failedAttempts),
                PASSWORD_CREDENTIALS.VERSION.eq(credential.version),
            ).execute() == 1

    fun clearAttempts(
        dsl: DSLContext,
        userId: UUID,
    ) {
        dsl
            .update(PASSWORD_CREDENTIALS)
            .set(PASSWORD_CREDENTIALS.FAILED_ATTEMPTS, 0)
            .setNull(PASSWORD_CREDENTIALS.NEXT_ATTEMPT_AT)
            .where(PASSWORD_CREDENTIALS.USER_ID.eq(userId))
            .execute()
    }

    /** Touches nothing when the password changed meanwhile: the new hash was computed from the old password. */
    fun upgradeHash(
        dsl: DSLContext,
        credential: PasswordCredential,
        hash: String,
    ) {
        dsl
            .update(PASSWORD_CREDENTIALS)
            .set(PASSWORD_CREDENTIALS.HASH, hash)
            .set(PASSWORD_CREDENTIALS.VERSION, credential.version + 1)
            .where(PASSWORD_CREDENTIALS.USER_ID.eq(credential.userId), PASSWORD_CREDENTIALS.VERSION.eq(credential.version))
            .execute()
    }

    private fun toCredential(row: PasswordCredentialsRecord) =
        PasswordCredential(row.userId!!, row.hash!!, row.failedAttempts!!, row.nextAttemptAt?.toInstant(), row.version!!)
}
