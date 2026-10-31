package idp.worker.mail

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val LINK = "https://acme.idp.test/flow?token=secret"

class MailTemplatesTest :
    FunSpec({
        val templates = MailTemplates()

        MailTemplate.entries.forEach { template ->
            test("$template fills every placeholder") {
                val mail = templates.render(template, mapOf("tenant" to "Acme", "link" to LINK))

                listOf(mail.subject, mail.text, mail.html).forEach {
                    it shouldContain "Acme"
                    it shouldNotContain "{{"
                }
            }
        }

        listOf(MailTemplate.INVITATION, MailTemplate.PASSWORD_RESET, MailTemplate.EMAIL_CHANGE_CONFIRMATION).forEach { template ->
            test("$template carries the link in both bodies") {
                val mail = templates.render(template, mapOf("tenant" to "Acme", "link" to LINK))

                mail.text shouldContain LINK
                mail.html shouldContain """href="$LINK""""
            }
        }

        test("the HTML body escapes a value, the text body keeps it") {
            val mail =
                templates.render(
                    MailTemplate.EMAIL_CHANGED,
                    mapOf("tenant" to """<img src=x onerror="alert(1)">"""),
                )

            mail.html shouldNotContain "<img"
            mail.html shouldContain "&lt;img"
            mail.text shouldContain "<img"
        }

        test("a value is not expanded as a template") {
            val mail = templates.render(MailTemplate.INVITATION, mapOf("tenant" to "{{link}}", "link" to LINK))

            mail.subject shouldBe "You are invited to {{link}}"
        }

        test("a value cannot break out of its line") {
            val mail = templates.render(MailTemplate.EMAIL_CHANGED, mapOf("tenant" to "Acme\r\nBcc: mallory@evil.test"))

            mail.subject shouldBe "Your Acme Bcc: mallory@evil.test email was changed"
        }

        test("a missing variable names itself and shows no values") {
            val failure =
                shouldThrow<IllegalStateException> {
                    templates.render(MailTemplate.INVITATION, mapOf("tenant" to "secret-tenant"))
                }

            failure.message shouldContain "link"
            failure.message shouldNotContain "secret-tenant"
        }
    })
