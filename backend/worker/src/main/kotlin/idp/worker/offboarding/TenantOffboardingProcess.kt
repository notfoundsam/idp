package idp.worker.offboarding

import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.erasure.Erasure
import idp.sharedkernel.erasure.ErasureJournal
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.EventHandler
import idp.sharedkernel.outbox.EventScope
import idp.sharedkernel.outbox.OutboxEvent
import idp.sharedkernel.tenant.TenantStatus
import idp.tenancy.application.TenancyEvents
import idp.tenancy.application.TenancyNotFound
import idp.tenancy.application.TenancyService

/**
 * Runs when the waiting period ends. Every step is safe to repeat, and the status changes only after the last one, so the next
 * delivery after a crash finds the KEK URI in `platform.tenants` and finishes the job. [schemas] needs a direct connection,
 * see `SchemaLock`.
 */
class TenantOffboardingProcess(
    private val tenancy: TenancyService,
    private val schemas: TenantSchemaFactory,
    private val kms: KeyManagementService,
    private val journal: ErasureJournal,
) : EventHandler {
    override val name: String = "tenant-offboarding"
    override val eventType: String = TenancyEvents.OFFBOARDING_STARTED
    override val scope: EventScope = EventScope.PLATFORM

    /** Another version means the operator cancelled, and a new start publishes an event of its own. */
    override fun handle(event: OutboxEvent) {
        val id = event.aggregateId
        val tenant = tenancy.tenant(id) ?: throw TenancyNotFound(id)
        if (tenant.status != TenantStatus.OFFBOARDING || tenant.version != event.aggregateVersion) return
        tenancy.ensureOffboardingDue(id)
        // First, so a backup restored after any of the next steps does not bring the tenant back.
        journal.record(Erasure.Tenant(id))
        tenant.keks.forEach(kms::destroy)
        schemas.drop(id)
        tenancy.delete(id)
    }
}
