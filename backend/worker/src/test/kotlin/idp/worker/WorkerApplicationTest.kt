package idp.worker

import idp.audit.application.AuditService
import idp.audit.application.OperatorAuditService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantStatus
import idp.sharedkernel.throttle.RateLimiter
import idp.tenancy.application.NewApplication
import idp.tenancy.application.TenancyService
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.runApplication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.seconds

private fun WorkerEnvironment.operatorService(): TenancyService {
    val platform = PlatformTransaction(database.pooled("ops_runtime"))
    return TenancyService(
        platform,
        OperatorAuditService(platform),
        OutboxPublisher(),
        FieldCipher.platform(kms, platformKekUri),
        BASE_DOMAIN,
    )
}

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

        "the audit log of worker writes to the tenant journal" {
            runApplication<WorkerApplication>(*arguments).use { context ->
                context.getBean(AuditLog::class.java).shouldBeInstanceOf<AuditService>()
            }
        }

        "new keys need a configured adapter" {
            shouldThrowAny { runApplication<WorkerApplication>(*arguments, "--KMS_NEW_KEYS=aws-kms") }
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

        "the timer runs the outbox cleanup and a cleanup job per tenant cleanup" {
            val isolated = WorkerEnvironment()

            runApplication<WorkerApplication>(*isolated.arguments, "--idp.jobs.tick-interval=PT0.05S").use {
                eventually(30.seconds) {
                    isolated.jdbc.queryForList("SELECT name FROM platform.scheduled_locks", String::class.java) shouldContainAll
                        listOf("cleanup:outbox", "cleanup:processed-events")
                }
            }
        }
    })
