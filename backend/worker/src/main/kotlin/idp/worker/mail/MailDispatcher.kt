package idp.worker.mail

import org.slf4j.LoggerFactory
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import java.util.UUID

private val log = LoggerFactory.getLogger(MailDispatcher::class.java)

/** Not a data class: variables hold one-time tokens, and a generated `toString` would print them. */
class Mail(
    val userId: UUID,
    val template: MailTemplate,
    val variables: Map<String, String>,
)

/** Names only the type of the cause: SMTP errors quote the address, and `OutboxDispatcher` logs what it catches in full. */
class MailNotSent(
    mail: Mail,
    failure: MailException,
) : RuntimeException("Mail ${mail.template} for user ${mail.userId} failed: ${failure.mostSpecificCause.javaClass.simpleName}")

/**
 * Call it from an [idp.sharedkernel.outbox.EventHandler] with `EventScope.TENANT`. A failure rolls back
 * `processed_events`, so the retry sends the mail, and a finished delivery never sends it again.
 */
class MailDispatcher(
    private val sender: JavaMailSender,
    private val recipients: MailRecipients,
    private val templates: MailTemplates,
    private val from: String,
) {
    fun send(mail: Mail) {
        val address = recipients.addressOf(mail.userId)
        if (address == null) {
            log.info("Mail {} dropped: user {} has no address", mail.template, mail.userId)
            return
        }
        val content = templates.render(mail.template, mail.variables)
        try {
            sender.send { message ->
                MimeMessageHelper(message, true, Charsets.UTF_8.name()).run {
                    setFrom(from)
                    setTo(address)
                    setSubject(content.subject)
                    setText(content.text, content.html)
                }
            }
        } catch (failure: MailException) {
            throw MailNotSent(mail, failure)
        }
        log.info("Mail {} sent to user {}", mail.template, mail.userId)
    }
}
