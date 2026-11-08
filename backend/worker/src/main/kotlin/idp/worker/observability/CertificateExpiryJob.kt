package idp.worker.observability

import idp.federation.application.FederationService
import idp.sharedkernel.observability.FailureSource
import idp.sharedkernel.observability.Failures
import idp.sharedkernel.scheduling.JobLease
import idp.sharedkernel.scheduling.ScheduledJob
import idp.sharedkernel.tenant.TenantContext
import idp.tenancy.application.TenancyService
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps the earliest end of a published signing certificate of any tenant. The gauge is a moment, not a remainder, so a value from
 * the last run of another worker stays true until a rotation. Certificates of SPs are left out: nothing here checks their dates.
 */
class CertificateExpiryJob(
    private val tenancy: TenancyService,
    private val federation: FederationService,
    private val failures: Failures,
    override val interval: Duration = Duration.ofHours(1),
) : ScheduledJob,
    MeterBinder {
    override val name: String = "certificate-expiry"

    private val earliest = AtomicReference<Instant?>()

    fun earliest(): Instant? = earliest.get()

    override fun run(lease: JobLease) {
        var found: Instant? = null
        for (tenant in tenancy.tenantsWithKek()) {
            if (!lease.renew()) return
            TenantContext.with(TenantContext(tenant.id, tenant.kekUri)) {
                runCatching { federation.signingKeys() }
                    .onSuccess { keys ->
                        keys.minOfOrNull { it.certificate.notAfter.toInstant() }?.let { end ->
                            found = found?.let { minOf(it, end) } ?: end
                        }
                    }.onFailure { failures.record(FailureSource.JOB, it) }
            }
        }
        earliest.set(found)
    }

    override fun bindTo(registry: MeterRegistry) {
        Gauge
            .builder("idp.saml.certificate.expiry", this) { job -> job.earliest()?.epochSecond?.toDouble() ?: Double.NaN }
            .baseUnit("seconds")
            .description("The earliest notAfter of a published signing certificate, in seconds since the epoch")
            .register(registry)
    }
}
