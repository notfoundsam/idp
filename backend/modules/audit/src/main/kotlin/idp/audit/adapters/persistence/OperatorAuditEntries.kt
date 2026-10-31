package idp.audit.adapters.persistence

import idp.db.jooq.platform.tables.OperatorAudit.OPERATOR_AUDIT
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import org.jooq.DSLContext
import org.jooq.JSONB
import tools.jackson.databind.json.JsonMapper

private const val ANONYMOUS = "anonymous"
private const val IP = "ip"
private const val TARGET = "target"

internal class OperatorAuditEntries {
    private val json = JsonMapper()

    fun add(
        dsl: DSLContext,
        record: AuditRecord,
    ) {
        dsl
            .insertInto(OPERATOR_AUDIT)
            .set(OPERATOR_AUDIT.ACTOR, nameOf(record.actor))
            .set(OPERATOR_AUDIT.ACTION, record.action)
            .set(OPERATOR_AUDIT.TENANT_ID, record.target?.takeIf { it.type == AuditTarget.TENANT }?.id)
            .set(OPERATOR_AUDIT.DETAILS, JSONB.valueOf(json.writeValueAsString(details(record))))
            .execute()
    }

    private fun nameOf(actor: Actor): String =
        when (actor) {
            is Actor.Operator -> actor.name
            Actor.Anonymous -> ANONYMOUS
            is Actor.User, Actor.System -> throw IllegalArgumentException("Only operators are recorded in platform.operator_audit")
        }

    /** The table has columns for neither, and an operator's address is not customer data. */
    private fun details(record: AuditRecord): Map<String, String> =
        record.details +
            listOfNotNull(
                record.ip?.let { IP to it },
                record.target?.let { TARGET to "${it.type}:${it.id}" },
            )
}
