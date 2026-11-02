package idp.identity.adapters.persistence

import idp.db.jooq.tenant.tables.EmailChangeNotices.EMAIL_CHANGE_NOTICES
import idp.db.jooq.tenant.tables.EmailChanges.EMAIL_CHANGES
import org.jooq.DSLContext
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class ConfirmedEmailChange(
    val userId: UUID,
    val newEmailCiphertext: ByteArray,
)

internal class EmailChanges {
    /** The old hash goes with the row, so a new request cancels the link of the old one. */
    fun replace(
        dsl: DSLContext,
        userId: UUID,
        newEmailCiphertext: ByteArray,
        expiresAt: Instant,
    ) {
        val expires = expiresAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(EMAIL_CHANGES)
            .set(EMAIL_CHANGES.USER_ID, userId)
            .set(EMAIL_CHANGES.NEW_EMAIL_CIPHERTEXT, newEmailCiphertext)
            .set(EMAIL_CHANGES.EXPIRES_AT, expires)
            .onConflict(EMAIL_CHANGES.USER_ID)
            .doUpdate()
            .set(EMAIL_CHANGES.NEW_EMAIL_CIPHERTEXT, newEmailCiphertext)
            .setNull(EMAIL_CHANGES.TOKEN_HASH)
            .set(EMAIL_CHANGES.EXPIRES_AT, expires)
            .execute()
    }

    /** False when no live request waits for a token. */
    fun attachToken(
        dsl: DSLContext,
        userId: UUID,
        tokenHash: ByteArray,
        now: Instant,
    ): Boolean =
        dsl
            .update(EMAIL_CHANGES)
            .set(EMAIL_CHANGES.TOKEN_HASH, tokenHash)
            .where(EMAIL_CHANGES.USER_ID.eq(userId), EMAIL_CHANGES.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
            .execute() == 1

    fun newEmailOf(
        dsl: DSLContext,
        userId: UUID,
        now: Instant,
    ): ByteArray? =
        dsl
            .select(EMAIL_CHANGES.NEW_EMAIL_CIPHERTEXT)
            .from(EMAIL_CHANGES)
            .where(EMAIL_CHANGES.USER_ID.eq(userId), EMAIL_CHANGES.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
            .fetchOne(EMAIL_CHANGES.NEW_EMAIL_CIPHERTEXT)

    /** One statement, so of two concurrent attempts only one gets the row. */
    fun consume(
        dsl: DSLContext,
        tokenHash: ByteArray,
        now: Instant,
    ): ConfirmedEmailChange? =
        dsl
            .deleteFrom(EMAIL_CHANGES)
            .where(EMAIL_CHANGES.TOKEN_HASH.eq(tokenHash), EMAIL_CHANGES.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
            .returningResult(EMAIL_CHANGES.USER_ID, EMAIL_CHANGES.NEW_EMAIL_CIPHERTEXT)
            .fetchOne { ConfirmedEmailChange(it.value1()!!, it.value2()!!) }

    fun delete(
        dsl: DSLContext,
        userId: UUID,
    ) {
        dsl.deleteFrom(EMAIL_CHANGES).where(EMAIL_CHANGES.USER_ID.eq(userId)).execute()
    }
}

internal class EmailChangeNotices {
    /** Keeps the row it finds: of two quick changes the first address is the one whose owner did not ask for them. */
    fun add(
        dsl: DSLContext,
        userId: UUID,
        oldEmailCiphertext: ByteArray,
        expiresAt: Instant,
    ) {
        dsl
            .insertInto(EMAIL_CHANGE_NOTICES)
            .set(EMAIL_CHANGE_NOTICES.USER_ID, userId)
            .set(EMAIL_CHANGE_NOTICES.OLD_EMAIL_CIPHERTEXT, oldEmailCiphertext)
            .set(EMAIL_CHANGE_NOTICES.EXPIRES_AT, expiresAt.atOffset(ZoneOffset.UTC))
            .onConflict(EMAIL_CHANGE_NOTICES.USER_ID)
            .doNothing()
            .execute()
    }

    fun oldEmailOf(
        dsl: DSLContext,
        userId: UUID,
    ): ByteArray? =
        dsl
            .select(EMAIL_CHANGE_NOTICES.OLD_EMAIL_CIPHERTEXT)
            .from(EMAIL_CHANGE_NOTICES)
            .where(EMAIL_CHANGE_NOTICES.USER_ID.eq(userId))
            .fetchOne(EMAIL_CHANGE_NOTICES.OLD_EMAIL_CIPHERTEXT)

    fun delete(
        dsl: DSLContext,
        userId: UUID,
    ) {
        dsl.deleteFrom(EMAIL_CHANGE_NOTICES).where(EMAIL_CHANGE_NOTICES.USER_ID.eq(userId)).execute()
    }
}
