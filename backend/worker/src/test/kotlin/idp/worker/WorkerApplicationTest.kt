package idp.worker

import idp.audit.application.AuditService
import idp.identity.application.IdentityEvents
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.erasure.ErasureJournal
import idp.sharedkernel.erasure.adapter.FileErasureJournal
import idp.sharedkernel.scheduling.TenantCleanup
import idp.sharedkernel.tenant.TenantStatus
import idp.sharedkernel.testing.OTLP_EXPORT
import idp.sharedkernel.testing.OTLP_SSL_BUNDLES
import idp.sharedkernel.testing.TestCa
import idp.sharedkernel.testing.TestMailpit
import idp.sharedkernel.throttle.RateLimiter
import idp.tenancy.application.NewApplication
import idp.tenancy.application.TenancyEvents
import idp.tenancy.application.TenancyService
import idp.worker.handlers.ErasureJournalHandler
import idp.worker.offboarding.TenantOffboardingProcess
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.springframework.boot.runApplication
import org.springframework.boot.ssl.SslBundles
import org.springframework.core.NestedExceptionUtils
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.X509Certificate
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

class WorkerApplicationTest :
    StringSpec({
        val environment = WorkerEnvironment()
        val arguments = environment.arguments

        "health answers on the management port" {
            runApplication<WorkerApplication>(*arguments).use { context ->
                val port = context.environment.getProperty("local.server.port")

                val health =
                    HttpClient
                        .newHttpClient()
                        .send(
                            HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health")).build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )

                health.statusCode() shouldBe 200
                health.body() shouldContain "\"status\":\"UP\""
            }
        }

        "an unreachable mail server does not fail health" {
            runApplication<WorkerApplication>(
                *arguments,
                "--spring.mail.host=127.0.0.1",
                "--spring.mail.port=1",
            ).use { context ->
                val port = context.environment.getProperty("local.server.port")

                val health =
                    HttpClient
                        .newHttpClient()
                        .send(
                            HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health")).build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )

                health.statusCode() shouldBe 200
            }
        }

        "worker creates no Valkey adapter" {
            runApplication<WorkerApplication>(*arguments).use { context ->
                context.getBeansOfType(RateLimiter::class.java).shouldBeEmpty()
            }
        }

        "accepted SAML requests and their IDs are cleaned up by a job" {
            runApplication<WorkerApplication>(*arguments).use { context ->
                context.getBeansOfType(TenantCleanup::class.java).values.map { it.name } shouldContain "saml-requests"
            }
        }

        "traces and metrics leave only for an OTLP endpoint" {
            runApplication<WorkerApplication>(*arguments).use { context ->
                context.getBeansOfType(SpanExporter::class.java).shouldBeEmpty()
                context.getBeansOfType(OtlpMeterRegistry::class.java).shouldBeEmpty()
            }
            runApplication<WorkerApplication>(*arguments, *OTLP_EXPORT).use { context ->
                context.getBeansOfType(SpanExporter::class.java).shouldNotBeEmpty()
                context.getBeansOfType(OtlpMeterRegistry::class.java).shouldNotBeEmpty()
            }
        }

        "every exporter trusts the CA in OTLP_TLS_CA alone" {
            runApplication<WorkerApplication>(*arguments, *OTLP_EXPORT, "--OTLP_TLS_CA=${TestCa("Test collector CA").pem}").use { context ->
                OTLP_SSL_BUNDLES.forEach { context.environment.getProperty(it) shouldBe "otlp" }
                val bundle = context.getBean(SslBundles::class.java).getBundle("otlp")
                val store = bundle.stores.trustStore.shouldNotBeNull()
                store.aliases().toList().map { (store.getCertificate(it) as X509Certificate).subjectX500Principal.name } shouldBe
                    listOf("CN=Test collector CA")
            }
        }

        "the prod profile refuses to export over plain HTTP" {
            val failure = shouldThrowAny { runApplication<WorkerApplication>(*arguments, *OTLP_EXPORT, "--spring.profiles.active=prod") }

            NestedExceptionUtils.getMostSpecificCause(failure).message.shouldNotBeNull() shouldContain
                "An OTLP endpoint without TLS is refused in the prod profile"
        }

        "the audit log of worker writes to the tenant journal" {
            runApplication<WorkerApplication>(*arguments).use { context ->
                context.getBean(AuditLog::class.java).shouldBeInstanceOf<AuditService>()
            }
        }

        "new keys need a configured adapter" {
            shouldThrowAny { runApplication<WorkerApplication>(*arguments, "--KMS_NEW_KEYS=aws-kms") }
        }

        "Terraform sets how long a KEK replaced by a move is kept" {
            runApplication<WorkerApplication>(*arguments, "--KMS_PREVIOUS_KEK_RETENTION=45d").use { context ->
                context.environment.getProperty("idp.kms.previous-kek-retention", Duration::class.java) shouldBe Duration.ofDays(45)
            }
        }

        "worker does not start with a retention that is no duration" {
            shouldThrowAny { runApplication<WorkerApplication>(*arguments, "--KMS_PREVIOUS_KEK_RETENTION=forever") }
        }

        "worker does not start without an erasure journal" {
            val withoutJournal = arguments.filterNot { it.startsWith("--ERASURE_JOURNAL_FILE=") }.toTypedArray()

            shouldThrowAny { runApplication<WorkerApplication>(*withoutJournal) }.stackTraceToString() shouldContain
                "The erasure journal needs either a bucket or a file"
        }

        "the offboarding and the user erasure listen to their events through the beans of worker" {
            runApplication<WorkerApplication>(*arguments).use { context ->
                context.getBean(TenantOffboardingProcess::class.java).eventType shouldBe TenancyEvents.OFFBOARDING_STARTED
                context.getBean(ErasureJournalHandler::class.java).eventType shouldBe IdentityEvents.ERASED
                context.getBean(ErasureJournal::class.java).shouldBeInstanceOf<FileErasureJournal>()
            }
        }

        "an approved tenant becomes active through the outbox" {
            val operator = environment.operatorService()
            val application = operator.submit(NewApplication("Acme", "acme", "admin@acme.example"), Actor.Operator("alice"))

            runApplication<WorkerApplication>(*arguments).use { context ->
                operator.approve(application, Actor.Operator("alice"))
                val tenant = operator.application(application).shouldNotBeNull().tenantId

                eventually(30.seconds) { context.getBean(TenancyService::class.java).tenant(tenant)?.status shouldBe TenantStatus.ACTIVE }
            }
        }

        "the first admin of an approved tenant gets the invitation through the beans of worker" {
            val isolated = WorkerEnvironment()
            val operator = isolated.operatorService()
            val application = operator.submit(NewApplication("Acme", "acme", "admin@acme.example"), Actor.Operator("alice"))
            val mailpit = TestMailpit.start()

            mailpit.use {
                runApplication<WorkerApplication>(
                    *isolated.arguments,
                    "--spring.mail.host=${mailpit.host}",
                    "--spring.mail.port=${mailpit.smtpPort}",
                    "--spring.mail.properties.mail.smtp.auth=false",
                    "--spring.mail.properties.mail.smtp.starttls.enable=false",
                    "--spring.mail.properties.mail.smtp.starttls.required=false",
                ).use {
                    operator.approve(application, Actor.Operator("alice"))

                    eventually(30.seconds) {
                        mailpit
                            .messages()
                            .single()
                            .to
                            .shouldContainExactly("admin@acme.example")
                    }
                    mailpit.messages().single().from shouldBe "no-reply@$BASE_DOMAIN"
                }
            }
        }

        "the SMTP client verifies the host name and refuses TLS older than 1.2" {
            runApplication<WorkerApplication>(*arguments).use { context ->
                val settings = (context.getBean(JavaMailSender::class.java) as JavaMailSenderImpl).javaMailProperties

                settings["mail.smtp.ssl.checkserveridentity"].toString() shouldBe "true"
                settings["mail.smtp.ssl.protocols"] shouldBe "TLSv1.3 TLSv1.2"
            }
        }

        "the timer runs the outbox cleanup and a cleanup job per tenant cleanup" {
            val isolated = WorkerEnvironment()

            runApplication<WorkerApplication>(*isolated.arguments, "--idp.jobs.tick-interval=PT0.05S").use {
                eventually(30.seconds) {
                    isolated.jdbc.queryForList("SELECT name FROM platform.scheduled_locks", String::class.java) shouldContainAll
                        listOf("cleanup:outbox", "cleanup:processed-events", "cleanup:invitations", "cleanup:flows", "cleanup:sso-sessions")
                }
            }
        }
    })
