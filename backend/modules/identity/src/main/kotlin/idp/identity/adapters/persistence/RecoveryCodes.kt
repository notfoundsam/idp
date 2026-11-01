package idp.identity.adapters.persistence

import idp.db.jooq.tenant.tables.RecoveryCodes.RECOVERY_CODES
import org.jooq.DSLContext
import java.util.UUID

internal class RecoveryCodes {
    fun replace(
        dsl: DSLContext,
        userId: UUID,
        hashes: List<ByteArray>,
    ) {
        dsl.deleteFrom(RECOVERY_CODES).where(RECOVERY_CODES.USER_ID.eq(userId)).execute()
        dsl
            .batch(
                hashes.map { hash ->
                    dsl.insertInto(RECOVERY_CODES).set(RECOVERY_CODES.USER_ID, userId).set(RECOVERY_CODES.CODE_HASH, hash)
                },
            ).execute()
    }

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
