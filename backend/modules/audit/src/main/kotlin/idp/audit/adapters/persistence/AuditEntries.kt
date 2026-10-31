package idp.audit.adapters.persistence

import idp.audit.application.AuditCursor
import idp.audit.domain.AuditEntry
import idp.db.jooq.tenant.tables.AuditEntries.AUDIT_ENTRIES
import idp.db.jooq.tenant.tables.records.AuditEntriesRecord
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditTarget
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.ZoneOffset

private const val USER = "USER"
private const val SYSTEM = "SYSTEM"
private const val ANONYMOUS = "ANONYMOUS"

internal class AuditEntries {
    fun add(
        dsl: DSLContext,
        entry: AuditEntry,
    ) {
        dsl
            .insertInto(AUDIT_ENTRIES)
            .set(AUDIT_ENTRIES.ID, entry.id)
            .set(AUDIT_ENTRIES.OCCURRED_AT, entry.occurredAt.atOffset(ZoneOffset.UTC))
            .set(AUDIT_ENTRIES.ACTION, entry.action)
            .set(AUDIT_ENTRIES.ACTOR_TYPE, typeOf(entry.actor))
            .set(AUDIT_ENTRIES.ACTOR_ID, entry.keyOwner)
            .set(AUDIT_ENTRIES.TARGET_TYPE, entry.target?.type)
            .set(AUDIT_ENTRIES.TARGET_ID, entry.target?.id)
            .set(AUDIT_ENTRIES.IP_CIPHERTEXT, entry.ipCiphertext)
            .set(AUDIT_ENTRIES.DETAILS_CIPHERTEXT, entry.detailsCiphertext)
            .execute()
    }

    fun page(
        dsl: DSLContext,
        limit: Int,
        before: AuditCursor?,
    ): List<AuditEntry> {
        val position =
            before?.let {
                DSL.row(AUDIT_ENTRIES.OCCURRED_AT, AUDIT_ENTRIES.ID).lessThan(it.occurredAt.atOffset(ZoneOffset.UTC), it.id)
            }
        return dsl
            .selectFrom(AUDIT_ENTRIES)
            .where(position ?: DSL.noCondition())
            .orderBy(AUDIT_ENTRIES.OCCURRED_AT.desc(), AUDIT_ENTRIES.ID.desc())
            .limit(limit)
            .fetch(::toEntry)
    }

    private fun typeOf(actor: Actor): String =
        when (actor) {
            is Actor.User -> USER
            Actor.System -> SYSTEM
            Actor.Anonymous -> ANONYMOUS
            is Actor.Operator -> throw IllegalArgumentException("Operators are recorded in platform.operator_audit")
        }

    private fun toEntry(row: AuditEntriesRecord) =
        AuditEntry(
            id = row.id,
            occurredAt = row.occurredAt.toInstant(),
            action = row.action,
            actor = actorOf(row),
            target = row.targetType?.let { AuditTarget(it, row.targetId) },
            ipCiphertext = row.ipCiphertext,
            detailsCiphertext = row.detailsCiphertext,
        )

    private fun actorOf(row: AuditEntriesRecord): Actor =
        when (row.actorType) {
            USER -> Actor.User(row.actorId)
            SYSTEM -> Actor.System
            ANONYMOUS -> Actor.Anonymous
            else -> error("Unknown actor type ${row.actorType}")
        }
}
