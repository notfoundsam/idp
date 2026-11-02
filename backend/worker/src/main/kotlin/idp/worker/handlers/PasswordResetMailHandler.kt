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
 * The address comes from the stored row, never from what was typed: the blind index folds spelling, so another spelling finds this
 * user without being this mailbox. The token follows the rules of [InvitationMailHandler].
 */
class PasswordResetMailHandler(
    private val identity: IdentityService,
    private val tenancy: TenancyService,
    private val mail: MailDispatcher,
) : EventHandler {
    override val name: String = "password-reset-mail"
    override val eventType: String = IdentityEvents.PASSWORD_RESET_REQUESTED
    override val scope: EventScope = EventScope.TENANT

    override fun handle(event: OutboxEvent) {
        val token = identity.issuePasswordReset(event.aggregateId, Actor.System) ?: return
        val site = tenancy.siteOf(event)
        val link = "https://${site.host}/reset-password#token=$token"
        mail.send(Mail(event.aggregateId, MailTemplate.PASSWORD_RESET, mapOf("tenant" to site.name, "link" to link)))
    }
}
