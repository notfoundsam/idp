package idp.sharedkernel.audit

/** Synchronous, because a record lost in a queue is a sensitive action nobody can account for. */
interface AuditLog {
    /** Joins the caller's transaction: an action that rolled back did not happen. */
    fun recordChange(record: AuditRecord)

    /** Commits on its own: a denied action usually rolls back, and its record must stay. */
    fun recordDenial(record: AuditRecord)
}
