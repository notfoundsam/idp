package idp.identity.adapters.persistence

import idp.db.jooq.tenant.tables.PasskeyCredentials.PASSKEY_CREDENTIALS
import idp.db.jooq.tenant.tables.RecoveryCodes.RECOVERY_CODES
import idp.db.jooq.tenant.tables.TotpCredentials.TOTP_CREDENTIALS
import org.jooq.DSLContext
import org.jooq.impl.DSL.notExists
import org.jooq.impl.DSL.selectOne
import java.util.UUID

internal class RecoveryCodes {
    fun replace(
        dsl: DSLContext,
        userId: UUID,
        hashes: List<ByteArray>,
    ) {
        delete(dsl, userId)
        dsl
            .batch(
                hashes.map { hash ->
                    dsl.insertInto(RECOVERY_CODES).set(RECOVERY_CODES.USER_ID, userId).set(RECOVERY_CODES.CODE_HASH, hash)
                },
            ).execute()
    }

    fun delete(
        dsl: DSLContext,
        userId: UUID,
    ) {
        dsl.deleteFrom(RECOVERY_CODES).where(RECOVERY_CODES.USER_ID.eq(userId)).execute()
    }

    /** Codes stand in for a second factor, so they go with the last one, and the next enrollment issues new ones. True when they went. */
    fun deleteWithoutSecondFactor(
        dsl: DSLContext,
        userId: UUID,
    ): Boolean =
        dsl
            .deleteFrom(RECOVERY_CODES)
            .where(
                RECOVERY_CODES.USER_ID.eq(userId),
                notExists(
                    selectOne()
                        .from(TOTP_CREDENTIALS)
                        .where(TOTP_CREDENTIALS.USER_ID.eq(userId), TOTP_CREDENTIALS.CONFIRMED_AT.isNotNull),
                ),
                notExists(selectOne().from(PASSKEY_CREDENTIALS).where(PASSKEY_CREDENTIALS.USER_ID.eq(userId))),
            ).execute() > 0

    fun count(
        dsl: DSLContext,
        userId: UUID,
    ): Int = dsl.fetchCount(RECOVERY_CODES, RECOVERY_CODES.USER_ID.eq(userId))

    /** One statement, so of two concurrent uses of a code only one finds its row. */
    fun consume(
        dsl: DSLContext,
        userId: UUID,
        hash: ByteArray,
    ): Boolean =
        dsl
            .deleteFrom(RECOVERY_CODES)
            .where(RECOVERY_CODES.USER_ID.eq(userId), RECOVERY_CODES.CODE_HASH.eq(hash))
            .execute() == 1
}
