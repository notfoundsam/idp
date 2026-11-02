package idp.app

import idp.audit.application.AuditService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.testing.OTLP_EXPORT
import idp.sharedkernel.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.springframework.boot.runApplication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class AppApplicationTest :
    StringSpec({
        val database = TestDatabase.migrated()
        database.role("app_runtime")
        val arguments =
            arrayOf(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.datasource.url=${database.url}",
                "--spring.datasource.username=app_runtime",
                "--spring.datasource.password=app_runtime",
                "--VALKEY_URL=redis://127.0.0.1:1",
                "--THROTTLE_IP_HMAC_KEY=test-key",
                "--KMS_NEW_KEYS=hcvault",
                "--OPENBAO_ADDRESS=http://127.0.0.1:1",
            )

        fun get(
            port: String?,
            path: String,
        ): HttpResponse<String> =
            HttpClient
                .newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI("http://localhost:$port$path")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

        "health answers on the management port only" {
            runApplication<AppApplication>(*arguments).use { context ->
                val main = context.environment.getProperty("local.server.port")
                val management = context.environment.getProperty("local.management.port")

                val health = get(management, "/actuator/health")
                health.statusCode() shouldBe 200
                health.body() shouldContain "\"status\":\"UP\""

                get(main, "/actuator/health").statusCode() shouldBe 404
            }
        }

        "the management port exposes nothing but health" {
            runApplication<AppApplication>(*arguments).use { context ->
                val management = context.environment.getProperty("local.management.port")

                get(management, "/actuator/env").statusCode() shouldBe 404
            }
        }

        "the bus and the audit log of app write to the tenant journal" {
            runApplication<AppApplication>(*arguments).use { context ->
                context.getBean(AuditLog::class.java).shouldBeInstanceOf<AuditService>()
                context.getBean(CommandBus::class.java).shouldNotBeNull()
                context.getBean(QueryBus::class.java).shouldNotBeNull()
            }
        }

        "new keys need a configured adapter" {
            shouldThrowAny { runApplication<AppApplication>(*arguments, "--KMS_NEW_KEYS=aws-kms") }
        }

        "traces and metrics leave only for an OTLP endpoint" {
            runApplication<AppApplication>(*arguments).use { context ->
                context.getBeansOfType(SpanExporter::class.java).shouldBeEmpty()
                context.getBeansOfType(OtlpMeterRegistry::class.java).shouldBeEmpty()
            }
            runApplication<AppApplication>(*arguments, *OTLP_EXPORT).use { context ->
                context.getBeansOfType(SpanExporter::class.java).shouldNotBeEmpty()
                context.getBeansOfType(OtlpMeterRegistry::class.java).shouldNotBeEmpty()
            }
        }
    })
