package idp.sharedkernel.outbox

import idp.db.jooq.platform.tables.Outbox.OUTBOX
import idp.sharedkernel.tenant.PlatformTransaction
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

private const val PENDING = "PENDING"
private const val DEAD = "DEAD"
private const val MILLIS_PER_SECOND = 1000.0

/**
 * The lag counts from `available_at`, not from the age of a row: offboarding plans its event 30 days ahead, and a retry waits out
 * its delay. A claimed event is due again only when its lease ends, so the lag also shows a worker that died with a claim.
 */
class OutboxMetrics(
    private val platform: PlatformTransaction,
    private val clock: Clock = Clock.systemUTC(),
) : MeterBinder {
    fun lag(): Duration {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val oldest =
            platform.execute { dsl ->
                dsl
                    .select(DSL.min(OUTBOX.AVAILABLE_AT))
                    .from(OUTBOX)
                    .where(OUTBOX.STATUS.eq(PENDING), OUTBOX.AVAILABLE_AT.le(now))
                    .fetchOne()
                    ?.value1()
            }
        return oldest?.let { Duration.between(it, now) } ?: Duration.ZERO
    }

    fun dead(): Int = platform.execute { dsl -> dsl.fetchCount(OUTBOX, OUTBOX.STATUS.eq(DEAD)) }

    override fun bindTo(registry: MeterRegistry) {
        Gauge
            .builder("idp.outbox.lag", this) { it.lag().toMillis() / MILLIS_PER_SECOND }
            .baseUnit("seconds")
            .description("How long the most overdue pending event has waited past its available_at")
            .register(registry)
        Gauge
            .builder("idp.outbox.dead", this) { it.dead().toDouble() }
            .description("Events that ran out of attempts")
            .register(registry)
    }
}
