package idp.audit.application

import idp.audit.adapters.persistence.OperatorAuditEntries
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.tenant.PlatformTransaction

/** The `AuditLog` of `ops`: `ops_runtime` reaches neither tenant schemas nor `Decrypt`, so this log needs no keys. */
class OperatorAuditService(
    private val transaction: PlatformTransaction,
) : AuditLog {
    private val entries = OperatorAuditEntries()

    override fun recordChange(record: AuditRecord) {
        transaction.join { dsl -> entries.add(dsl, record) }
    }

    override fun recordDenial(record: AuditRecord) {
        transaction.executeSeparately { dsl -> entries.add(dsl, record) }
    }
}
