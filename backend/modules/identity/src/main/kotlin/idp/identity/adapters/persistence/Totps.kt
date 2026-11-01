package idp.identity.adapters.persistence

import idp.db.jooq.tenant.tables.TotpCredentials.TOTP_CREDENTIALS
import idp.db.jooq.tenant.tables.records.TotpCredentialsRecord
import idp.identity.domain.credential.TotpCredential
import org.jooq.DSLContext
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class Totps {
    fun find(
        dsl: DSLContext,
        userId: UUID,
    ): TotpCredential? = dsl.selectFrom(TOTP_CREDENTIALS).where(TOTP_CREDENTIALS.USER_ID.eq(userId)).fetchOne(::toCredential)

    /** One statement, so an enrollment cannot overwrite a secret confirmed a moment ago. False when one is confirmed. */
    fun begin(
        dsl: DSLContext,
        userId: UUID,
        secretCiphertext: ByteArray,
    ): Boolean =
        dsl
            .insertInto(TOTP_CREDENTIALS)
            .set(TOTP_CREDENTIALS.USER_ID, userId)
            .set(TOTP_CREDENTIALS.SECRET_CIPHERTEXT, secretCiphertext)
            .onConflict(TOTP_CREDENTIALS.USER_ID)
            .doUpdate()
            .set(TOTP_CREDENTIALS.SECRET_CIPHERTEXT, secretCiphertext)
            .set(TOTP_CREDENTIALS.FAILED_ATTEMPTS, 0)
            .setNull(TOTP_CREDENTIALS.NEXT_ATTEMPT_AT)
            .set(TOTP_CREDENTIALS.VERSION, TOTP_CREDENTIALS.VERSION.plus(1))
            .where(TOTP_CREDENTIALS.CONFIRMED_AT.isNull)
            .execute() == 1

    /** One statement, so of two concurrent attempts only one goes on, and a parallel burst cannot outrun the delay. */
    fun claimAttempt(
        dsl: DSLContext,
        credential: TotpCredential,
        now: Instant,
    ): Boolean =
        dsl
            .update(TOTP_CREDENTIALS)
            .set(TOTP_CREDENTIALS.FAILED_ATTEMPTS, TOTP_CREDENTIALS.FAILED_ATTEMPTS.plus(1))
            .set(TOTP_CREDENTIALS.NEXT_ATTEMPT_AT, credential.nextAttemptAfterFailure(now)?.atOffset(ZoneOffset.UTC))
            .where(
                TOTP_CREDENTIALS.USER_ID.eq(credential.userId),
                TOTP_CREDENTIALS.FAILED_ATTEMPTS.eq(credential.failedAttempts),
                TOTP_CREDENTIALS.VERSION.eq(credential.version),
            ).execute() == 1

    /** Refusing a step that is not newer is what makes a code work once: a check before this update would lose to a parallel request. */
    fun markUsed(
        dsl: DSLContext,
        credential: TotpCredential,
        step: Long,
    ): Boolean =
        dsl
            .update(TOTP_CREDENTIALS)
            .set(TOTP_CREDENTIALS.LAST_USED_STEP, step)
            .set(TOTP_CREDENTIALS.FAILED_ATTEMPTS, 0)
            .setNull(TOTP_CREDENTIALS.NEXT_ATTEMPT_AT)
            .where(
                TOTP_CREDENTIALS.USER_ID.eq(credential.userId),
                TOTP_CREDENTIALS.VERSION.eq(credential.version),
                TOTP_CREDENTIALS.LAST_USED_STEP.isNull.or(TOTP_CREDENTIALS.LAST_USED_STEP.lt(step)),
            ).execute() == 1

    /** The version keeps out a code of a secret that a new enrollment has replaced. */
    fun confirm(
        dsl: DSLContext,
        credential: TotpCredential,
        step: Long,
        now: Instant,
    ): Boolean =
        dsl
            .update(TOTP_CREDENTIALS)
            .set(TOTP_CREDENTIALS.CONFIRMED_AT, now.atOffset(ZoneOffset.UTC))
            .set(TOTP_CREDENTIALS.LAST_USED_STEP, step)
            .set(TOTP_CREDENTIALS.FAILED_ATTEMPTS, 0)
            .setNull(TOTP_CREDENTIALS.NEXT_ATTEMPT_AT)
            .set(TOTP_CREDENTIALS.VERSION, credential.version + 1)
            .where(
                TOTP_CREDENTIALS.USER_ID.eq(credential.userId),
                TOTP_CREDENTIALS.VERSION.eq(credential.version),
                TOTP_CREDENTIALS.CONFIRMED_AT.isNull,
            ).execute() == 1

    private fun toCredential(row: TotpCredentialsRecord) =
        TotpCredential(
            row.userId!!,
            row.secretCiphertext!!,
            row.confirmedAt?.toInstant(),
            row.lastUsedStep,
            row.failedAttempts!!,
            row.nextAttemptAt?.toInstant(),
            row.version!!,
        )
}
