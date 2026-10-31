package idp.sharedkernel.outbox

import idp.db.jooq.platform.tables.Outbox.OUTBOX
import idp.sharedkernel.tenant.TenantContext
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

data class NewEvent(
    val aggregateType: String,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val eventType: String,
    val payload: Any = emptyMap<String, Any>(),
)

/** Bound around a handler, so a reaction joins the chain of its cause without passing IDs by hand. */
internal data class EventCause(
    val correlationId: UUID,
    val eventId: UUID?,
) {
    companion object {
        private val current: ScopedValue<EventCause> = ScopedValue.newInstance()

        fun currentOrNull(): EventCause? = if (current.isBound) current.get() else null

        fun <T> with(
            cause: EventCause,
            block: () -> T,
        ): T = ScopedValue.where(current, cause).call(ScopedValue.CallableOp<T, Exception> { block() })
    }
}

class OutboxPublisher {
    /** Takes the caller's transaction, because the event must commit or roll back with the aggregate. */
    fun publish(
        dsl: DSLContext,
        event: NewEvent,
    ) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "An event is published inside a transaction" }
        val cause = EventCause.currentOrNull()
        dsl
            .insertInto(OUTBOX)
            .set(OUTBOX.ID, UUID.randomUUID())
            .set(OUTBOX.TENANT_ID, TenantContext.currentOrNull()?.tenantId)
            .set(OUTBOX.AGGREGATE_TYPE, event.aggregateType)
            .set(OUTBOX.AGGREGATE_ID, event.aggregateId)
            .set(OUTBOX.AGGREGATE_VERSION, event.aggregateVersion)
            .set(OUTBOX.EVENT_TYPE, event.eventType)
            .set(OUTBOX.PAYLOAD, JSONB.valueOf(eventJson.writeValueAsString(event.payload)))
            .set(OUTBOX.CORRELATION_ID, cause?.correlationId ?: UUID.randomUUID())
            .set(OUTBOX.CAUSATION_ID, cause?.eventId)
            .execute()
    }
}
