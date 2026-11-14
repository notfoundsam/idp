package idp.sharedkernel.outbox

import idp.db.jooq.platform.tables.Outbox.OUTBOX
import idp.sharedkernel.observability.LogFields
import idp.sharedkernel.tenant.TenantContext
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** An event with [availableAt] waits in the outbox until then, which is how a decision gets a waiting period. */
data class NewEvent(
    val aggregateType: String,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val eventType: String,
    val payload: Any = emptyMap<String, Any>(),
    val availableAt: Instant? = null,
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
        ): T =
            LogFields.with(LogFields.CORRELATION_ID, cause.correlationId.toString()) {
                ScopedValue.where(current, cause).call(ScopedValue.CallableOp<T, Exception> { block() })
            }
    }
}

/** Stamps `available_at` from the JVM clock, which the dispatcher and the lag gauge compare it with, never from the database's. */
class OutboxPublisher(
    private val clock: Clock = Clock.systemUTC(),
) {
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
            .set(OUTBOX.AVAILABLE_AT, (event.availableAt ?: clock.instant()).atOffset(ZoneOffset.UTC))
            .execute()
    }
}
