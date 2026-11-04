package idp.audience.admin.application.query

import idp.audit.application.AuditCursor
import idp.audit.application.AuditEntryView
import idp.audit.application.AuditService
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.Query

class ReadJournal(
    val limit: Int,
    val before: AuditCursor?,
    val target: AuditTarget?,
) : Query<List<AuditEntryView>>

class ReadJournalHandler(
    private val audit: AuditService,
) : MessageHandler<ReadJournal, List<AuditEntryView>> {
    override val messageType = ReadJournal::class

    override fun handle(message: ReadJournal): List<AuditEntryView> = audit.read(message.limit, message.before, message.target)
}
