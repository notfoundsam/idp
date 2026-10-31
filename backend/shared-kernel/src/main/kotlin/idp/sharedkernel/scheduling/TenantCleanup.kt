package idp.sharedkernel.scheduling

import org.jooq.DSLContext
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * How a module expires rows of its tenant tables. It implements this in `adapters/persistence` and declares a bean,
 * and `worker` runs every bean as a `TenantCleanupJob`.
 */
interface TenantCleanup {
    val name: String

    /** Runs in a transaction of the tenant. `now` is one value for the whole run. */
    fun delete(
        dsl: DSLContext,
        now: OffsetDateTime,
    )
}

/** One job per cleanup, so a broken cleanup of one module rolls back nothing of another. */
class TenantCleanupJob(
    private val cleanup: TenantCleanup,
    private val tenants: TenantJobRunner,
    private val clock: Clock = Clock.systemUTC(),
    override val interval: Duration = Duration.ofHours(1),
) : ScheduledJob {
    override val name: String = "cleanup:${cleanup.name}"

    override fun run(lease: JobLease) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        tenants.forEachTenant(name, lease) { cleanup.delete(it, now) }
    }
}
