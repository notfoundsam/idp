package idp.worker.handlers

import idp.identity.application.IdentityService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.scheduling.TenantCleanup
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.testing.ReceivedMail
import idp.sharedkernel.testing.TestMailpit
import idp.worker.WorkerApplication
import idp.worker.WorkerEnvironment
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.runApplication
import org.springframework.context.ConfigurableApplicationContext
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private const val EMAIL = "ada@acme.test"
private const val NEW_EMAIL = "ada.byron@acme.test"
private const val HOST = "acme.idp.test"
private val TOKEN = Regex("""#token=([\w-]+)""")

private fun WorkerEnvironment.tenant(context: ConfigurableApplicationContext): TenantContext {
    val id = database.tenantSchema()
    val kekUri = kms.createKey("tenant-${UUID.randomUUID()}")
    jdbc.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES (?, 'Acme', 'ACTIVE', ?)", id, kekUri)
    jdbc.update("INSERT INTO platform.tenant_domains (domain, tenant_id) VALUES (?, ?)", "${id.toString().take(8)}.$HOST", id)
    return TenantContext(id, kekUri).also { tenant ->
        TenantContext.with(tenant) { context.getBean(KeyHierarchy::class.java).createTenantKey() }
    }
}

private fun tokenOf(mail: ReceivedMail): String = TOKEN.find(mail.text).shouldNotBeNull().groupValues[1]

class RecoveryMailTest :
    FunSpec({
        val mailpit = TestMailpit.start()
        val environment = WorkerEnvironment()
        val context =
            runApplication<WorkerApplication>(
                *environment.arguments,
                "--spring.mail.host=${mailpit.host}",
                "--spring.mail.port=${mailpit.smtpPort}",
                "--spring.mail.properties.mail.smtp.auth=false",
                "--spring.mail.properties.mail.smtp.starttls.enable=false",
                "--spring.mail.properties.mail.smtp.starttls.required=false",
            )
        val identity = context.getBean(IdentityService::class.java)

        afterSpec {
            context.close()
            mailpit.close()
        }

        beforeTest { mailpit.clear() }

        suspend fun mails(count: Int): List<ReceivedMail> {
            lateinit var received: List<ReceivedMail>
            eventually(30.seconds) {
                received = mailpit.messages()
                received shouldHaveSize count
            }
            return received
        }

        /** The invitation of the user is drained first, so a test sees only its own mail. */
        suspend fun TenantContext.active(email: String = EMAIL): UUID {
            val user = TenantContext.with(this) { identity.invite(email, UserRole.USER, Actor.System) }
            val invitation = mails(1).single()
            TenantContext.with(this) { identity.acceptInvitation(tokenOf(invitation), Actor.Anonymous) }
            mailpit.clear()
            return user
        }

        /** Every event of the tenant has been handled, so a mail that did not come will not come. */
        suspend fun TenantContext.drained() {
            eventually(30.seconds) {
                environment.jdbc.queryForObject(
                    "SELECT count(*) FROM platform.outbox WHERE tenant_id = ? AND status <> 'DONE'",
                    Int::class.java,
                    tenantId,
                ) shouldBe 0
            }
        }

        fun TenantContext.table(name: String) = "\"${TenantSchema(tenantId).name}\".$name"

        fun TenantContext.host(): String =
            environment.jdbc.queryForObject(
                "SELECT domain FROM platform.tenant_domains WHERE tenant_id = ?",
                String::class.java,
                tenantId,
            )!!

        context("a password reset") {
            test("reaches the user with a working link that keeps the token in the fragment") {
                val tenant = environment.tenant(context)
                val user = tenant.active()

                TenantContext.with(tenant) { identity.requestPasswordReset(EMAIL) }

                val mail = mails(1).single()
                mail.to.shouldContainExactly(EMAIL)
                mail.subject shouldBe "Reset your Acme password"
                mail.text shouldContain "https://${tenant.host()}/reset-password#token="
                mail.text shouldNotContain "?token"
                mail.html shouldNotContain "?token"
                TenantContext.with(tenant) { identity.passwordResetOwner(tokenOf(mail)) } shouldBe user
            }

            test("goes to the stored address, however the login was typed") {
                val tenant = environment.tenant(context)
                tenant.active()

                TenantContext.with(tenant) { identity.requestPasswordReset("ADA@ACME.TEST") }

                mails(1).single().to.shouldContainExactly(EMAIL)
            }

            test("sends nothing for a login that matched nobody, and the event finishes") {
                val tenant = environment.tenant(context)

                TenantContext.with(tenant) { identity.requestPasswordReset("nobody@acme.test") }

                tenant.drained()
                mailpit.messages().shouldBeEmpty()
            }

            test("sends nothing to a blocked user") {
                val tenant = environment.tenant(context)
                val user = tenant.active()
                TenantContext.with(tenant) { identity.block(user, Actor.System) }

                TenantContext.with(tenant) { identity.requestPasswordReset(EMAIL) }

                tenant.drained()
                mailpit.messages().shouldBeEmpty()
            }
        }

        context("an email change") {
            test("asks the new address and keeps the token in the fragment") {
                val tenant = environment.tenant(context)
                val user = tenant.active()

                TenantContext.with(tenant) { identity.requestEmailChange(user, NEW_EMAIL, Actor.User(user)) }

                val mail = mails(1).single()
                mail.to.shouldContainExactly(NEW_EMAIL)
                mail.subject shouldBe "Confirm your new Acme email"
                mail.text shouldContain "https://${tenant.host()}/confirm-email#token="
                mail.text shouldNotContain "?token"
            }

            test("tells the old address once the new one confirms, and forgets it after that") {
                val tenant = environment.tenant(context)
                val user = tenant.active()
                TenantContext.with(tenant) { identity.requestEmailChange(user, NEW_EMAIL, Actor.User(user)) }
                val token = tokenOf(mails(1).single())
                mailpit.clear()

                TenantContext.with(tenant) { identity.confirmEmailChange(token) }

                val notice = mails(1).single()
                notice.to.shouldContainExactly(EMAIL)
                notice.subject shouldBe "Your Acme email was changed"
                tenant.drained()
                environment.jdbc.queryForObject(
                    "SELECT count(*) FROM ${tenant.table("email_change_notices")}",
                    Int::class.java,
                ) shouldBe 0
            }
        }

        context("the tokens of recovery") {
            test("are cleaned up by a job of worker") {
                context.getBeansOfType(TenantCleanup::class.java).values.map { it.name } shouldContain "recovery-tokens"
            }
        }
    })
