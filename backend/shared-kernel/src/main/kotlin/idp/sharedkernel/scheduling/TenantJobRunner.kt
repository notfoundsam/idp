package idp.sharedkernel.scheduling

import idp.db.jooq.platform.tables.Tenants.TENANTS
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantStatus
import idp.sharedkernel.tenant.TenantTransaction
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(TenantJobRunner::class.java)

/** Suspended and offboarding tenants keep their data, and data with a TTL must expire there too. */
private val SCHEMA_IN_USE = listOf(TenantStatus.ACTIVE, TenantStatus.SUSPENDED, TenantStatus.OFFBOARDING).map { it.name }

/** Each tenant runs in its own transaction, so a failure rolls back that tenant alone and the job goes on. */
class TenantJobRunner(
    private val platform: PlatformTransaction,
    private val transaction: TenantTransaction,
) {
    /** The lease is renewed before each tenant, so hundreds of schemas cannot outrun it. */
    fun forEachTenant(
        job: String,
        lease: JobLease,
        block: (DSLContext) -> Unit,
    ) {
        for (tenant in tenants()) {
            if (!lease.renew()) {
                log.warn("Job {} lost its lease and stops", job)
                return
            }
            runCatching { TenantContext.with(tenant) { transaction.execute(block) } }
                .onFailure { log.error("Job {} failed for tenant {}", job, tenant.tenantId, it) }
        }
    }

    private fun tenants(): List<TenantContext> =
        platform.execute { dsl ->
            dsl
                .select(TENANTS.ID, TENANTS.KEK_URI)
                .from(TENANTS)
                .where(TENANTS.STATUS.`in`(SCHEMA_IN_USE))
                .orderBy(TENANTS.ID)
                .fetch { TenantContext(it.value1()!!, it.value2()) }
        }
}
