package idp.sharedkernel.outbox

import idp.db.jooq.tenant.tables.ProcessedEvents.PROCESSED_EVENTS
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction

class IdempotentHandler(
    private val transaction: TenantTransaction,
) {
    fun deliver(
        event: OutboxEvent,
        kekUri: String?,
        handler: EventHandler,
    ) {
        when (handler.scope) {
            EventScope.PLATFORM -> handler.handle(event)
            EventScope.TENANT -> {
                val tenantId = checkNotNull(event.tenantId) { "Event ${event.id} has no tenant for ${handler.name}" }
                TenantContext.with(TenantContext(tenantId, kekUri)) { once(event, handler) }
            }
        }
    }

    /** A concurrent delivery waits on the primary key here, so it sees the outcome of the first one. */
    private fun once(
        event: OutboxEvent,
        handler: EventHandler,
    ) {
        transaction.execute { dsl ->
            val first =
                dsl
                    .insertInto(PROCESSED_EVENTS)
                    .set(PROCESSED_EVENTS.EVENT_ID, event.id)
                    .set(PROCESSED_EVENTS.HANDLER, handler.name)
                    .onConflictDoNothing()
                    .execute() == 1
            if (first) handler.handle(event)
        }
    }
}
