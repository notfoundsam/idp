package idp.worker.provisioning

import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.EventHandler
import idp.sharedkernel.outbox.EventScope
import idp.sharedkernel.outbox.OutboxEvent
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantStatus
import idp.tenancy.application.TenancyEvents
import idp.tenancy.application.TenancyNotFound
import idp.tenancy.application.TenancyService
import java.util.UUID

/** The name is stored in `platform.tenants`, so renaming a step breaks tenants that stopped on it. */
private class Step(
    val name: String,
    /** Returns the KEK URI when the step created it. */
    val run: (UUID) -> String?,
)

/**
 * Every step is safe to repeat, because a worker can die after finishing one and before recording it.
 * [schemas] and [migrations] need a direct connection, see `SchemaLock`.
 */
class TenantProvisioningProcess(
    private val tenancy: TenancyService,
    private val schemas: TenantSchemaFactory,
    private val migrations: TenantMigrationRunner,
    private val kms: KeyManagementService,
    private val keys: KeyHierarchy,
) : EventHandler {
    override val name: String = "tenant-provisioning"
    override val eventType: String = TenancyEvents.APPROVED
    override val scope: EventScope = EventScope.PLATFORM

    private val steps =
        listOf(
            Step("schema") { id ->
                schemas.create(id)
                null
            },
            Step("migrations") { id ->
                migrations.migrateTenant(id)
                null
            },
            Step("keys", ::createKeys),
        )

    override fun handle(event: OutboxEvent) {
        val id = event.aggregateId
        val tenant = tenancy.tenant(id) ?: throw TenancyNotFound(id)
        when (tenant.status) {
            TenantStatus.APPROVED -> tenancy.startProvisioning(id)
            TenantStatus.PROVISIONING -> Unit
            else -> return
        }
        stepsAfter(id, tenant.provisioningStep).forEach { step -> tenancy.recordProvisioningStep(id, step.name, step.run(id)) }
        tenancy.activate(id)
    }

    /** A step this release does not know comes from a newer one, and activating here would skip the steps it added. */
    private fun stepsAfter(
        id: UUID,
        last: String?,
    ): List<Step> {
        if (last == null) return steps
        val finished = steps.indexOfFirst { it.name == last }
        check(finished >= 0) { "Tenant $id stopped on step $last, which this release does not know" }
        return steps.drop(finished + 1)
    }

    private fun createKeys(id: UUID): String {
        val kekUri = kms.createKey("tenant-$id")
        TenantContext.with(TenantContext(id, kekUri)) { keys.createTenantKey() }
        return kekUri
    }
}
