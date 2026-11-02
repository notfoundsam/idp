package idp.migrator

import idp.identity.application.IdentityService
import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.erasure.Erasure
import idp.sharedkernel.erasure.ErasureJournal
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantStatus
import idp.tenancy.application.TenancyService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import java.util.UUID

private val log = LoggerFactory.getLogger(ReapplyErasuresJob::class.java)

data class ReappliedErasures(
    val tenants: Int,
    val users: Int,
)

/**
 * After a restore the database may hold tenants and people that asked to go. This runs after the migrations and before `app` and
 * `worker` start, see `docs/security/restore-runbook.md`. Tenants go first, because the users of a deleted tenant went with its
 * schema.
 */
@Order(2)
class ReapplyErasuresJob(
    private val journal: ErasureJournal,
    private val tenancy: TenancyService,
    private val identity: IdentityService,
    private val schemas: TenantSchemaFactory,
    private val kms: KeyManagementService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val applied = reapply()
        log.info("Erasures applied again: {} tenants, {} users", applied.tenants, applied.users)
    }

    fun reapply(): ReappliedErasures {
        val erasures = journal.entries()
        val tenants = erasures.filterIsInstance<Erasure.Tenant>().map { it.tenantId }.toSet()
        return ReappliedErasures(
            tenants = tenants.count(::deleteTenant),
            users = erasures.filterIsInstance<Erasure.User>().filter { it.tenantId !in tenants }.count(::eraseUser),
        )
    }

    /** The KEK and the schema go again too: `worker` records the tenant first, so the backup may hold either. */
    private fun deleteTenant(id: UUID): Boolean {
        val tenant = tenancy.tenant(id) ?: return false
        tenant.kekUri?.let(kms::destroy)
        schemas.drop(id)
        return tenancy.deleteAgain(id)
    }

    private fun eraseUser(erasure: Erasure.User): Boolean {
        val tenant = tenancy.tenant(erasure.tenantId) ?: return false
        if (tenant.status == TenantStatus.DELETED || !schemas.exists(tenant.id)) return false
        return TenantContext.with(TenantContext(tenant.id, tenant.kekUri)) { identity.eraseAgain(erasure.userId) }
    }
}
