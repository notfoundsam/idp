package idp.sharedkernel.outbox

import idp.db.jooq.platform.tables.Outbox.OUTBOX
import idp.db.jooq.platform.tables.Tenants.TENANTS
import idp.sharedkernel.tenant.PlatformTransaction
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

private const val PENDING = "PENDING"
internal const val DONE = "DONE"
private const val DEAD = "DEAD"

private val log = LoggerFactory.getLogger(OutboxDispatcher::class.java)

private data class Claim(
    val event: OutboxEvent,
    val kekUri: String?,
)

/**
 * A claim is a lease, not a held lock: a handler runs in a tenant transaction, which cannot share a connection with
 * the platform one. A worker that dies leaves the event to come back when the lease ends, and `attempts` counts that too.
 */
class OutboxDispatcher(
    private val platform: PlatformTransaction,
    private val idempotent: IdempotentHandler,
    handlers: List<EventHandler>,
    private val clock: Clock = Clock.systemUTC(),
    private val batchSize: Int = 100,
    private val lease: Duration = Duration.ofMinutes(5),
    private val retryDelay: Duration = Duration.ofSeconds(30),
    private val maxAttempts: Int = 8,
) {
    private val handlers = handlers.groupBy { it.eventType }

    /** The longest an event can keep coming back: every attempt holds a lease, and every retry waits out its delay. */
    val redeliveryWindow: Duration =
        lease.multipliedBy(maxAttempts.toLong()).plus(retryDelay.multipliedBy((1L shl (maxAttempts - 1)) - 1))

    init {
        val names = handlers.map { it.name }
        require(names.size == names.toSet().size) { "A handler name is its key in processed_events, so it must be unique: $names" }
    }

    /** Returns how many events it ran, so a caller can drain the queue until it gets zero. */
    fun dispatch(): Int {
        val claims = claim()
        claims.forEach(::deliver)
        return claims.size
    }

    private fun claim(): List<Claim> =
        platform.execute { dsl ->
            val claimed = selectDue(dsl)
            val (exhausted, runnable) = claimed.partition { it.event.attempts > maxAttempts }
            dsl
                .update(OUTBOX)
                .set(OUTBOX.STATUS, DEAD)
                .where(OUTBOX.ID.`in`(exhausted.map { it.event.id }))
                .execute()
            dsl
                .update(OUTBOX)
                .set(OUTBOX.ATTEMPTS, OUTBOX.ATTEMPTS.plus(1))
                .set(OUTBOX.AVAILABLE_AT, now().plus(lease))
                .where(OUTBOX.ID.`in`(runnable.map { it.event.id }))
                .execute()
            exhausted.forEach { log.error("Outbox event {} is dead: its workers did not finish", it.event.id) }
            runnable
        }

    /** Takes turns by tenant: the n-th event of every tenant goes before the (n+1)-th event of any tenant. */
    private fun selectDue(dsl: DSLContext): List<Claim> {
        // Repeated outside the ranking: a row changed by another worker is rechecked against the outer conditions only.
        val due = OUTBOX.STATUS.eq(PENDING).and(OUTBOX.AVAILABLE_AT.le(now()))
        val turn =
            DSL
                .rowNumber()
                .over(DSL.partitionBy(OUTBOX.TENANT_ID).orderBy(OUTBOX.AVAILABLE_AT, OUTBOX.ID))
                .`as`("turn")
        val ranked =
            dsl
                .select(OUTBOX.ID, turn)
                .from(OUTBOX)
                .where(due)
                .asTable("ranked")
        return dsl
            .select(*OUTBOX.fields(), TENANTS.KEK_URI)
            .from(OUTBOX)
            .join(ranked)
            .on(OUTBOX.ID.eq(ranked.field(OUTBOX.ID)))
            .leftJoin(TENANTS)
            .on(TENANTS.ID.eq(OUTBOX.TENANT_ID))
            .where(due)
            .orderBy(ranked.field(turn), OUTBOX.AVAILABLE_AT, OUTBOX.ID)
            .limit(batchSize)
            .forUpdate()
            .of(OUTBOX)
            .skipLocked()
            .fetch { row ->
                Claim(
                    OutboxEvent(
                        id = row[OUTBOX.ID]!!,
                        tenantId = row[OUTBOX.TENANT_ID],
                        aggregateType = row[OUTBOX.AGGREGATE_TYPE]!!,
                        aggregateId = row[OUTBOX.AGGREGATE_ID]!!,
                        aggregateVersion = row[OUTBOX.AGGREGATE_VERSION]!!,
                        eventType = row[OUTBOX.EVENT_TYPE]!!,
                        correlationId = row[OUTBOX.CORRELATION_ID]!!,
                        causationId = row[OUTBOX.CAUSATION_ID],
                        attempts = row[OUTBOX.ATTEMPTS]!! + 1,
                        json = row[OUTBOX.PAYLOAD]!!.data(),
                    ),
                    row[TENANTS.KEK_URI],
                )
            }
    }

    private fun deliver(claim: Claim) {
        val event = claim.event
        val failure =
            runCatching {
                EventCause.with(EventCause(event.correlationId, event.id)) {
                    handlers[event.eventType].orEmpty().forEach { idempotent.deliver(event, claim.kekUri, it) }
                }
            }.exceptionOrNull()
        when {
            failure == null -> settle(event, DONE, now())
            event.attempts >= maxAttempts -> {
                log.error("Outbox event {} is dead after {} attempts", event.id, event.attempts, failure)
                settle(event, DEAD, now())
            }
            else -> {
                log.warn("Outbox event {} failed on attempt {}", event.id, event.attempts, failure)
                settle(event, PENDING, now().plus(retryDelay.multipliedBy(1L shl (event.attempts - 1))))
            }
        }
    }

    /** Matches on `attempts`, so a worker that outlived its lease cannot overwrite the claim of the next one. */
    private fun settle(
        event: OutboxEvent,
        status: String,
        availableAt: OffsetDateTime,
    ) {
        platform.execute { dsl ->
            dsl
                .update(OUTBOX)
                .set(OUTBOX.STATUS, status)
                .set(OUTBOX.AVAILABLE_AT, availableAt)
                .where(OUTBOX.ID.eq(event.id), OUTBOX.ATTEMPTS.eq(event.attempts))
                .execute()
        }
    }

    private fun now(): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)
}
