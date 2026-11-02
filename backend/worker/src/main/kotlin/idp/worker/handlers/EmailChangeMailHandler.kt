package idp.worker.handlers

import idp.identity.application.IdentityEvents
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.outbox.EventHandler
import idp.sharedkernel.outbox.EventScope
import idp.sharedkernel.outbox.OutboxEvent
import idp.tenancy.application.TenancyService
import idp.worker.mail.Mail
import idp.worker.mail.MailAddress
import idp.worker.mail.MailDispatcher
import idp.worker.mail.MailTemplate

/** Goes to the new address: the link proves that mailbox. The token follows the rules of [InvitationMailHandler]. */
class EmailChangeMailHandler(
    private val identity: IdentityService,
    private val tenancy: TenancyService,
    private val mail: MailDispatcher,
) : EventHandler {
    override val name: String = "email-change-mail"
    override val eventType: String = IdentityEvents.EMAIL_CHANGE_REQUESTED
    override val scope: EventScope = EventScope.TENANT

    override fun handle(event: OutboxEvent) {
        val token = identity.issueEmailChange(event.aggregateId, Actor.System) ?: return
        val site = tenancy.siteOf(event)
        val link = "https://${site.host}/confirm-email#token=$token"
        mail.send(
            Mail(
                event.aggregateId,
                MailTemplate.EMAIL_CHANGE_CONFIRMATION,
                mapOf("tenant" to site.name, "link" to link),
                MailAddress.PENDING,
            ),
        )
    }
}

/** Goes to the old address, whose owner may not be the one who asked. A failed send rolls the row back, so the retry finds the address. */
class EmailChangedMailHandler(
    private val identity: IdentityService,
    private val tenancy: TenancyService,
    private val mail: MailDispatcher,
) : EventHandler {
    override val name: String = "email-changed-mail"
    override val eventType: String = IdentityEvents.EMAIL_CHANGED
    override val scope: EventScope = EventScope.TENANT

    override fun handle(event: OutboxEvent) {
        val site = tenancy.siteOf(event)
        mail.send(Mail(event.aggregateId, MailTemplate.EMAIL_CHANGED, mapOf("tenant" to site.name), MailAddress.PREVIOUS))
        identity.forgetPreviousEmail(event.aggregateId)
    }
}
