package idp.worker.handlers

import idp.sharedkernel.outbox.OutboxEvent
import idp.tenancy.application.TenancyService
import idp.tenancy.application.TenantSite

internal fun TenancyService.siteOf(event: OutboxEvent): TenantSite {
    val tenantId = checkNotNull(event.tenantId) { "Event ${event.id} has no tenant" }
    return checkNotNull(site(tenantId)) { "Tenant $tenantId has no site" }
}
