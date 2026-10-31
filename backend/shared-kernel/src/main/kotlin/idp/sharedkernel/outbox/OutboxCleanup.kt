package idp.sharedkernel.outbox

import idp.db.jooq.platform.tables.Outbox.OUTBOX
import idp.db.jooq.tenant.tables.ProcessedEvents.PROCESSED_EVENTS
import idp.sharedkernel.scheduling.JobLease
import idp.sharedkernel.scheduling.ScheduledJob
import idp.sharedkernel.scheduling.TenantCleanup
import idp.sharedkernel.tenant.PlatformTransaction
import org.jooq.DSLContext
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A dead event stays until someone looks at it. A finished one has `available_at` set to the time it finished. */
class OutboxCleanupJob(
    private val platform: PlatformTransaction,
    private val clock: Clock = Clock.systemUTC(),
    private val retention: Duration = Duration.ofDays(7),
    override val interval: Duration = Duration.ofHours(1),
) : ScheduledJob {
    override val name: String = "cleanup:outbox"

    override fun run(lease: JobLease) {
        val cutoff = clock.instant().atOffset(ZoneOffset.UTC).minus(retention)
        platform.execute { dsl ->
            dsl
                .deleteFrom(OUTBOX)
                .where(OUTBOX.STATUS.eq(DONE), OUTBOX.AVAILABLE_AT.lt(cutoff))
                .execute()
        }
    }
}

/** A row gone before the last redelivery of its event would run the handler a second time. */
class ProcessedEventsCleanup(
    redeliveryWindow: Duration,
    private val ttl: Duration = Duration.ofDays(7),
) : TenantCleanup {
    override val name: String = "processed-events"

    init {
        require(ttl > redeliveryWindow) { "TTL $ttl must exceed the redelivery window $redeliveryWindow" }
    }

    override fun delete(
        dsl: DSLContext,
        now: OffsetDateTime,
    ) {
        dsl
            .deleteFrom(PROCESSED_EVENTS)
            .where(PROCESSED_EVENTS.PROCESSED_AT.lt(now.minus(ttl)))
            .execute()
    }
}
