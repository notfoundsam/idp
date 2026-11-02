package idp.worker.handlers

import idp.authentication.application.AuthenticationService
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.outbox.EventHandler
import idp.sharedkernel.outbox.EventScope
import idp.sharedkernel.outbox.OutboxEvent
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.tenancy.application.TenancyEvents
import idp.tenancy.application.TenancyNotFound
import idp.tenancy.application.TenancyService

/**
 * `ops` cannot reach a tenant, so this does the work. It runs in the platform scope, where a tenant role cannot get in the way of
 * reading the contact. The contact goes last: a worker that dies before that repeats the step, which only replaces the invitation.
 * The invitation itself leaves through the usual `user.invited`.
 */
class ReinviteAdminHandler(
    private val tenancy: TenancyService,
    private val identity: IdentityService,
    private val authentication: AuthenticationService,
    private val transaction: TenantTransaction,
) : EventHandler {
    override val name: String = "admin-reinvite"
    override val eventType: String = TenancyEvents.ADMIN_REINVITE_REQUESTED
    override val scope: EventScope = EventScope.PLATFORM

    override fun handle(event: OutboxEvent) {
        val contact = tenancy.reinviteContact(event.aggregateId) ?: return
        val tenantId = checkNotNull(event.tenantId) { "Event ${event.id} has no tenant" }
        val tenant = tenancy.tenant(tenantId) ?: throw TenancyNotFound(tenantId)
        TenantContext.with(TenantContext(tenantId, tenant.kekUri)) {
            transaction.atomically {
                val admin = identity.reinviteAdmin(contact)
                authentication.revokeUserSessions(admin, Actor.System)
            }
        }
        tenancy.eraseReinvite(event.aggregateId)
    }
}
