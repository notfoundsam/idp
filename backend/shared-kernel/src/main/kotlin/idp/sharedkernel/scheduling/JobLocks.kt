package idp.sharedkernel.scheduling

import idp.db.jooq.platform.tables.ScheduledLocks.SCHEDULED_LOCKS
import idp.sharedkernel.tenant.PlatformTransaction
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * A lock is a row with an end time: PgBouncer in transaction mode hands a session advisory lock to the next client,
 * and a job outlives its transactions. A worker that dies leaves the job to come back when the lease ends.
 */
class JobLocks(
    private val platform: PlatformTransaction,
    private val clock: Clock = Clock.systemUTC(),
    private val lease: Duration = Duration.ofMinutes(10),
) {
    /** Returns null while another worker runs the job or its interval has not passed. */
    fun acquire(name: String): JobLease? {
        val holder = UUID.randomUUID()
        val started = now()
        val until = started.plus(lease)
        val acquired =
            platform.execute { dsl ->
                dsl
                    .insertInto(SCHEDULED_LOCKS)
                    .set(SCHEDULED_LOCKS.NAME, name)
                    .set(SCHEDULED_LOCKS.LOCKED_UNTIL, until)
                    .set(SCHEDULED_LOCKS.HOLDER, holder)
                    .onConflict(SCHEDULED_LOCKS.NAME)
                    .doUpdate()
                    .set(SCHEDULED_LOCKS.LOCKED_UNTIL, until)
                    .set(SCHEDULED_LOCKS.HOLDER, holder)
                    .where(SCHEDULED_LOCKS.LOCKED_UNTIL.le(started))
                    .execute() == 1
            }
        return if (acquired) JobLease(name, holder, started, this) else null
    }

    internal fun renew(
        name: String,
        holder: UUID,
    ): Boolean = lockUntil(name, holder, now().plus(lease))

    /** Matches on the holder, so a worker that outlived its lease cannot touch the lock of the next one. */
    internal fun lockUntil(
        name: String,
        holder: UUID,
        until: OffsetDateTime,
    ): Boolean =
        platform.execute { dsl ->
            dsl
                .update(SCHEDULED_LOCKS)
                .set(SCHEDULED_LOCKS.LOCKED_UNTIL, until)
                .where(SCHEDULED_LOCKS.NAME.eq(name), SCHEDULED_LOCKS.HOLDER.eq(holder))
                .execute() == 1
        }

    private fun now(): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)
}

class JobLease internal constructor(
    private val name: String,
    private val holder: UUID,
    private val started: OffsetDateTime,
    private val locks: JobLocks,
) {
    /** False means another worker took the job over, so this one must stop. */
    fun renew(): Boolean = locks.renew(name, holder)

    /** Keeps the lock until the next run, so the workers share one schedule instead of each keeping its own. */
    internal fun release(interval: Duration) {
        locks.lockUntil(name, holder, started.plus(interval))
    }
}
