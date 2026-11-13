package idp.worker

import idp.sharedkernel.observability.FailureSource
import idp.sharedkernel.observability.Failures
import idp.sharedkernel.observability.LogFields
import idp.sharedkernel.scheduling.JobLease
import idp.tenancy.application.TenancyService
import idp.tenancy.application.TenantView
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("idp.worker.jobs")

/** The lease is renewed before each tenant, and a failure stops that tenant alone. False when the lease was lost before the end. */
internal fun TenancyService.forEachTenantWithKek(
    job: String,
    lease: JobLease,
    failures: Failures,
    block: (TenantView) -> Unit,
): Boolean {
    for (tenant in tenantsWithKek()) {
        if (!lease.renew()) {
            log.warn("Job {} lost its lease and stops", job)
            return false
        }
        runCatching { block(tenant) }.onFailure {
            LogFields.with(LogFields.TENANT_ID, tenant.id.toString()) { failures.record(FailureSource.JOB, it, job) }
        }
    }
    return true
}
