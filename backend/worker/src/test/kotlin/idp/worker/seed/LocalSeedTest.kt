package idp.worker.seed

import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.testing.TestMailpit
import idp.tenancy.application.TenancyEvents
import idp.worker.WorkerApplication
import idp.worker.WorkerEnvironment
import idp.worker.mail.Mail
import idp.worker.mail.MailDispatcher
import idp.worker.mail.MailTemplate
import idp.worker.mail.MailTemplates
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.springframework.boot.runApplication
import org.springframework.mail.javamail.JavaMailSender
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

class LocalSeedTest :
    FunSpec({
        test("the seed does not run outside the local profile") {
            runApplication<WorkerApplication>(*WorkerEnvironment().arguments).use { context ->
                context.getBeansOfType(LocalSeed::class.java).keys.shouldBeEmpty()
            }
        }

        context("in the local profile") {
            val environment = WorkerEnvironment()
            val jdbc = environment.jdbc
            val arguments = environment.arguments + "--spring.profiles.active=local"

            fun tenants(): List<String> =
                jdbc
                    .queryForList(
                        """
                    SELECT d.domain FROM platform.tenant_domains d
                    JOIN platform.tenants t ON t.id = d.tenant_id WHERE t.status = 'ACTIVE'
                    """,
                        String::class.java,
                    ).filterNotNull()

            fun tenantIds(): List<UUID> = jdbc.queryForList("SELECT id FROM platform.tenants", UUID::class.java).filterNotNull()

            fun wrappedKey(id: UUID): ByteArray =
                jdbc.queryForObject("SELECT wrapped_key FROM \"${TenantSchema(id).name}\".tenant_keys", ByteArray::class.java)!!

            test("two active tenants appear with migrated schemas") {
                runApplication<WorkerApplication>(*arguments).use { context ->
                    tenants() shouldContainExactlyInAnyOrder listOf("acme.idp.test", "globex.idp.test")

                    tenantIds().forEach { id ->
                        val schema = TenantSchema(id).name
                        val history = "SELECT count(*) FROM \"$schema\".flyway_schema_history WHERE success"
                        (jdbc.queryForObject(history, Int::class.java)!! > 0) shouldBe true
                    }

                    val port = context.environment.getProperty("local.server.port")
                    val readiness =
                        HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health/readiness")).build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    readiness.statusCode() shouldBe 200
                }
            }

            test("the tenants come from approved applications, as in ops") {
                jdbc.queryForObject(
                    "SELECT count(*) FROM platform.tenant_applications WHERE status = 'APPROVED'",
                    Int::class.java,
                ) shouldBe 2
                jdbc.queryForList(
                    "SELECT status FROM platform.outbox WHERE event_type = ?",
                    String::class.java,
                    TenancyEvents.APPROVED,
                ) shouldContainExactlyInAnyOrder listOf("DONE", "DONE")
                jdbc.queryForObject(
                    "SELECT count(*) FROM platform.operator_audit WHERE action = 'tenant.approved' AND actor LIKE '%local-seed%'",
                    Int::class.java,
                ) shouldBe 2
            }

            test("each tenant gets a KEK and a tenant key under it") {
                val kms = environment.kms

                tenantIds().forEach { id ->
                    val kekUri = jdbc.queryForObject("SELECT kek_uri FROM platform.tenants WHERE id = ?", String::class.java, id)!!

                    kekUri shouldStartWith "hcvault://transit/"
                    kms.unwrap(kekUri, wrappedKey(id)).size shouldBe 32
                }
            }

            test("a restart changes nothing") {
                val keys = tenantIds().associateWith(::wrappedKey)

                runApplication<WorkerApplication>(*arguments).close()

                tenants() shouldContainExactlyInAnyOrder listOf("acme.idp.test", "globex.idp.test")
                jdbc.queryForObject("SELECT count(*) FROM platform.tenants", Int::class.java) shouldBe 2
                tenantIds().forEach { id -> wrappedKey(id) shouldBe keys[id] }
            }

            test("mail goes to Mailpit without TLS or a password") {
                TestMailpit.start().use { mailpit ->
                    val mailServer = arrayOf("--MAIL_HOST=${mailpit.host}", "--MAIL_PORT=${mailpit.smtpPort}")

                    runApplication<WorkerApplication>(*arguments, *mailServer).use { context ->
                        val sender = context.getBean(JavaMailSender::class.java)

                        MailDispatcher(sender, { "ada@acme.test" }, MailTemplates(), "no-reply@idp.test")
                            .send(Mail(UUID.randomUUID(), MailTemplate.EMAIL_CHANGED, mapOf("tenant" to "Acme")))

                        mailpit.messages() shouldHaveSize 1
                    }
                }
            }
        }
    })
