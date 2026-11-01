package idp.worker.handlers

import idp.authentication.application.AuthenticationService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.outbox.EventHandler
import idp.sharedkernel.outbox.EventScope
import idp.sharedkernel.outbox.OutboxEvent
import idp.tenancy.application.TenancyEvents

/** The status already closes the door at the host. This keeps the sessions from coming back to life when the tenant resumes. */
class TenantSuspendedHandler(
    private val authentication: AuthenticationService,
) : EventHandler {
    override val name: String = "tenant-suspended-sessions"
    override val eventType: String = TenancyEvents.SUSPENDED
    override val scope: EventScope = EventScope.TENANT

    override fun handle(event: OutboxEvent) {
        authentication.revokeAllSessions(Actor.System)
    }
}
