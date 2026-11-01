package idp.sharedkernel.outbox

import idp.db.jooq.tenant.tables.ProcessedEvents.PROCESSED_EVENTS
import idp.sharedkernel.persistence.VersionConflictException
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(IdempotentHandler::class.java)

class IdempotentHandler(
    private val transaction: TenantTransaction,
) {
    fun deliver(
        event: OutboxEvent,
        kekUri: String?,
        handler: EventHandler,
    ) {
        repeatOnConflict(event, handler) {
            when (handler.scope) {
                EventScope.PLATFORM -> handler.handle(event)
                EventScope.TENANT -> {
                    val tenantId = checkNotNull(event.tenantId) { "Event ${event.id} has no tenant for ${handler.name}" }
                    TenantContext.with(TenantContext(tenantId, kekUri)) { once(event, handler) }
                }
            }
        }
    }

    /** The other writer has already committed, so the next attempt reads its version. No need to spend a retry of the event. */
    private fun repeatOnConflict(
        event: OutboxEvent,
        handler: EventHandler,
        delivery: () -> Unit,
    ) {
        repeat(CONFLICT_ATTEMPTS - 1) { attempt ->
            try {
                return delivery()
            } catch (_: VersionConflictException) {
                log.debug("Event {} lost a version conflict in {} on attempt {}", event.id, handler.name, attempt + 1)
            }
        }
        delivery()
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

    companion object {
        const val CONFLICT_ATTEMPTS = 3
    }
}
