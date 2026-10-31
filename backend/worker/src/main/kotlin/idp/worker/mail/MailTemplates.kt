package idp.worker.mail

import org.springframework.core.io.ClassPathResource
import org.springframework.web.util.HtmlUtils

private val PLACEHOLDER = Regex("""\{\{(\w+)}}""")
private val CONTROL_CHARACTERS = Regex("""\p{Cntrl}+""")

enum class MailTemplate(
    val file: String,
    val subject: String,
) {
    INVITATION("invitation", "You are invited to {{tenant}}"),
    PASSWORD_RESET("password-reset", "Reset your {{tenant}} password"),
    EMAIL_CHANGE_CONFIRMATION("email-change-confirmation", "Confirm your new {{tenant}} email"),
    EMAIL_CHANGED("email-changed", "Your {{tenant}} email was changed"),
}

class RenderedMail(
    val subject: String,
    val text: String,
    val html: String,
)

/** Substitution only: a template engine would bring an expression language to a place that handles tenant-controlled text. */
class MailTemplates {
    private val texts = MailTemplate.entries.associateWith { source("${it.file}.txt") }
    private val htmls = MailTemplate.entries.associateWith { source("${it.file}.html") }

    fun render(
        template: MailTemplate,
        variables: Map<String, String>,
    ): RenderedMail {
        // Keeps tenant-controlled text on its line, without relying on the mail library to fold headers safely.
        val values = variables.mapValues { CONTROL_CHARACTERS.replace(it.value, " ") }
        return RenderedMail(
            subject = fill(template, template.subject, values),
            text = fill(template, texts.getValue(template), values),
            html = fill(template, htmls.getValue(template), values.mapValues { HtmlUtils.htmlEscape(it.value) }),
        )
    }

    private fun source(file: String): String = ClassPathResource("mail/$file").getContentAsString(Charsets.UTF_8)

    private fun fill(
        template: MailTemplate,
        source: String,
        values: Map<String, String>,
    ): String =
        PLACEHOLDER.replace(source) { match ->
            val name = match.groupValues[1]
            checkNotNull(values[name]) { "Mail template $template needs the variable $name" }
        }
}
