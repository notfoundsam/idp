package idp.worker.handlers

import idp.identity.application.IdentityEvents
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.outbox.EventHandler
import idp.sharedkernel.outbox.EventScope
import idp.sharedkernel.outbox.OutboxEvent
import idp.tenancy.application.TenancyService
import idp.worker.mail.Mail
import idp.worker.mail.MailDispatcher
import idp.worker.mail.MailTemplate

/**
 * The token is issued here, so it exists only in the mail. A failed send rolls it back and the retry issues a new one.
 * It goes in the fragment, which browsers keep out of requests, logs and `Referer`.
 */
class InvitationMailHandler(
    private val identity: IdentityService,
    private val tenancy: TenancyService,
    private val mail: MailDispatcher,
) : EventHandler {
    override val name: String = "invitation-mail"
    override val eventType: String = IdentityEvents.INVITED
    override val scope: EventScope = EventScope.TENANT

    override fun handle(event: OutboxEvent) {
        val tenantId = checkNotNull(event.tenantId) { "Event ${event.id} has no tenant" }
        val site = checkNotNull(tenancy.site(tenantId)) { "Tenant $tenantId has no site" }
        val token = identity.issueInvitation(event.aggregateId, Actor.System) ?: return
        val link = "https://${site.host}/invite#token=$token"
        mail.send(Mail(event.aggregateId, MailTemplate.INVITATION, mapOf("tenant" to site.name, "link" to link)))
    }
}
