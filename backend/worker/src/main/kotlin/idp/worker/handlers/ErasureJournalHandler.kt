package idp.worker.handlers

import idp.identity.application.IdentityEvents
import idp.sharedkernel.erasure.Erasure
import idp.sharedkernel.erasure.ErasureJournal
import idp.sharedkernel.outbox.EventHandler
import idp.sharedkernel.outbox.EventScope
import idp.sharedkernel.outbox.OutboxEvent

/** The platform scope: the journal needs no tenant transaction, and the tenant may be offboarded before the event comes. */
class ErasureJournalHandler(
    private val journal: ErasureJournal,
) : EventHandler {
    override val name: String = "user-erasure-journal"
    override val eventType: String = IdentityEvents.ERASED
    override val scope: EventScope = EventScope.PLATFORM

    override fun handle(event: OutboxEvent) {
        val tenantId = checkNotNull(event.tenantId) { "Event ${event.id} has no tenant" }
        journal.record(Erasure.User(tenantId, event.aggregateId))
    }
}
