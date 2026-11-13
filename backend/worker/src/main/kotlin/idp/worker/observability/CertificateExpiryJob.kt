package idp.worker.observability

import idp.federation.application.FederationService
import idp.sharedkernel.observability.Failures
import idp.sharedkernel.scheduling.JobLease
import idp.sharedkernel.scheduling.ScheduledJob
import idp.sharedkernel.tenant.TenantContext
import idp.tenancy.application.TenancyService
import idp.worker.forEachTenantWithKek
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the earliest end of a published signing certificate of any tenant. The gauge is a moment, not a remainder, so a value from
 * the last run of another worker stays true until a rotation. Certificates of SPs are left out: nothing here checks their dates.
 * A tenant whose keys cannot be read keeps the end read last time, so a failing read cannot hide a certificate that runs out.
 */
class CertificateExpiryJob(
    private val tenancy: TenancyService,
    private val federation: FederationService,
    private val failures: Failures,
    override val interval: Duration = Duration.ofHours(1),
) : ScheduledJob,
    MeterBinder {
    override val name: String = "certificate-expiry"

    private val ends = ConcurrentHashMap<UUID, Instant>()

    fun earliest(): Instant? = ends.values.minOrNull()

    override fun run(lease: JobLease) {
        val seen = mutableSetOf<UUID>()
        val completed =
            tenancy.forEachTenantWithKek(name, lease, failures) { tenant ->
                seen += tenant.id
                val keys = TenantContext.with(TenantContext(tenant.id, tenant.kekUri)) { federation.signingKeys() }
                val end = keys.minOfOrNull { it.certificate.notAfter.toInstant() }
                if (end == null) ends.remove(tenant.id) else ends[tenant.id] = end
            }
        if (completed) ends.keys.retainAll(seen)
    }

    override fun bindTo(registry: MeterRegistry) {
        Gauge
            .builder("idp.saml.certificate.expiry", this) { job -> job.earliest()?.epochSecond?.toDouble() ?: Double.NaN }
            .baseUnit("seconds")
            .description("The earliest notAfter of a published signing certificate, in seconds since the epoch")
            .register(registry)
    }
}
