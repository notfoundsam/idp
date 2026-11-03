package idp.federation.adapters.persistence

import idp.db.jooq.tenant.tables.SpAssignments.SP_ASSIGNMENTS
import idp.federation.domain.SpAssignment
import org.jooq.DSLContext
import java.time.ZoneOffset
import java.util.UUID

internal class SpAssignments {
    /** False when the pair is already there, so a repeat changes nothing. */
    fun add(
        dsl: DSLContext,
        assignment: SpAssignment,
    ): Boolean =
        dsl
            .insertInto(SP_ASSIGNMENTS)
            .set(SP_ASSIGNMENTS.SP_ID, assignment.spId)
            .set(SP_ASSIGNMENTS.USER_ID, assignment.userId)
            .set(SP_ASSIGNMENTS.ASSIGNED_AT, assignment.assignedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1

    /** False when there was nothing to remove. */
    fun delete(
        dsl: DSLContext,
        spId: UUID,
        userId: UUID,
    ): Boolean = dsl.deleteFrom(SP_ASSIGNMENTS).where(SP_ASSIGNMENTS.SP_ID.eq(spId), SP_ASSIGNMENTS.USER_ID.eq(userId)).execute() == 1

    fun exists(
        dsl: DSLContext,
        spId: UUID,
        userId: UUID,
    ): Boolean = dsl.fetchExists(SP_ASSIGNMENTS, SP_ASSIGNMENTS.SP_ID.eq(spId), SP_ASSIGNMENTS.USER_ID.eq(userId))

    fun usersOf(
        dsl: DSLContext,
        spId: UUID,
    ): List<UUID> =
        dsl
            .select(SP_ASSIGNMENTS.USER_ID)
            .from(SP_ASSIGNMENTS)
            .where(SP_ASSIGNMENTS.SP_ID.eq(spId))
            .orderBy(SP_ASSIGNMENTS.USER_ID)
            .fetch(SP_ASSIGNMENTS.USER_ID)
            .filterNotNull()

    fun spsOf(
        dsl: DSLContext,
        userId: UUID,
    ): List<UUID> =
        dsl
            .select(SP_ASSIGNMENTS.SP_ID)
            .from(SP_ASSIGNMENTS)
            .where(SP_ASSIGNMENTS.USER_ID.eq(userId))
            .orderBy(SP_ASSIGNMENTS.SP_ID)
            .fetch(SP_ASSIGNMENTS.SP_ID)
            .filterNotNull()
}
