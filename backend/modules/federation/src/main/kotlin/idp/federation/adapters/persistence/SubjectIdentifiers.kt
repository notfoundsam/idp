package idp.federation.adapters.persistence

import idp.db.jooq.tenant.tables.SubjectIdentifiers.SUBJECT_IDENTIFIERS
import org.jooq.DSLContext
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class SubjectIdentifiers {
    /** Keeps the value an earlier Response named, so of two parallel logins both name the same one. */
    fun valueOf(
        dsl: DSLContext,
        spId: UUID,
        userId: UUID,
        candidate: String,
        now: Instant,
    ): String {
        dsl
            .insertInto(SUBJECT_IDENTIFIERS)
            .set(SUBJECT_IDENTIFIERS.SP_ID, spId)
            .set(SUBJECT_IDENTIFIERS.USER_ID, userId)
            .set(SUBJECT_IDENTIFIERS.VALUE, candidate)
            .set(SUBJECT_IDENTIFIERS.CREATED_AT, now.atOffset(ZoneOffset.UTC))
            .onConflict(SUBJECT_IDENTIFIERS.SP_ID, SUBJECT_IDENTIFIERS.USER_ID)
            .doNothing()
            .execute()
        return dsl
            .select(SUBJECT_IDENTIFIERS.VALUE)
            .from(SUBJECT_IDENTIFIERS)
            .where(SUBJECT_IDENTIFIERS.SP_ID.eq(spId), SUBJECT_IDENTIFIERS.USER_ID.eq(userId))
            .fetchSingle(SUBJECT_IDENTIFIERS.VALUE)!!
    }
}
