package idp.identity.adapters.persistence

import idp.db.jooq.tenant.tables.EmailChangeNotices.EMAIL_CHANGE_NOTICES
import idp.db.jooq.tenant.tables.EmailChanges.EMAIL_CHANGES
import idp.db.jooq.tenant.tables.PasswordResets.PASSWORD_RESETS
import idp.sharedkernel.scheduling.TenantCleanup
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal class PasswordResets {
    /** The old hash goes with the row, so a new token cancels the old one. */
    fun replace(
        dsl: DSLContext,
        userId: UUID,
        tokenHash: ByteArray,
        expiresAt: Instant,
    ) {
        val expires = expiresAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(PASSWORD_RESETS)
            .set(PASSWORD_RESETS.USER_ID, userId)
            .set(PASSWORD_RESETS.TOKEN_HASH, tokenHash)
            .set(PASSWORD_RESETS.EXPIRES_AT, expires)
            .onConflict(PASSWORD_RESETS.USER_ID)
            .doUpdate()
            .set(PASSWORD_RESETS.TOKEN_HASH, tokenHash)
            .set(PASSWORD_RESETS.EXPIRES_AT, expires)
            .execute()
    }

    fun ownerOf(
        dsl: DSLContext,
        tokenHash: ByteArray,
        now: Instant,
    ): UUID? =
        dsl
            .select(PASSWORD_RESETS.USER_ID)
            .from(PASSWORD_RESETS)
            .where(PASSWORD_RESETS.TOKEN_HASH.eq(tokenHash), PASSWORD_RESETS.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
            .fetchOne(PASSWORD_RESETS.USER_ID)

    /** One statement, so of two concurrent attempts only one gets the row. */
    fun consume(
        dsl: DSLContext,
        tokenHash: ByteArray,
        now: Instant,
    ): UUID? =
        dsl
            .deleteFrom(PASSWORD_RESETS)
            .where(PASSWORD_RESETS.TOKEN_HASH.eq(tokenHash), PASSWORD_RESETS.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
            .returningResult(PASSWORD_RESETS.USER_ID)
            .fetchOne(PASSWORD_RESETS.USER_ID)

    fun delete(
        dsl: DSLContext,
        userId: UUID,
    ) {
        dsl.deleteFrom(PASSWORD_RESETS).where(PASSWORD_RESETS.USER_ID.eq(userId)).execute()
    }
}

internal class RecoveryCleanup : TenantCleanup {
    override val name: String = "recovery-tokens"

    override fun delete(
        dsl: DSLContext,
        now: OffsetDateTime,
    ) {
        dsl.deleteFrom(PASSWORD_RESETS).where(PASSWORD_RESETS.EXPIRES_AT.le(now)).execute()
        dsl.deleteFrom(EMAIL_CHANGES).where(EMAIL_CHANGES.EXPIRES_AT.le(now)).execute()
        dsl.deleteFrom(EMAIL_CHANGE_NOTICES).where(EMAIL_CHANGE_NOTICES.EXPIRES_AT.le(now)).execute()
    }
}
