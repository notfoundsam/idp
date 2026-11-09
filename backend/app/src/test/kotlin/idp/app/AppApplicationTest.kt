package idp.app

import idp.audit.application.AuditService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.observability.LogExport
import idp.sharedkernel.testing.OTLP_EXPORT
import idp.sharedkernel.testing.OTLP_SSL_BUNDLES
import idp.sharedkernel.testing.TestCa
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.web.TlsGuard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.springframework.boot.runApplication
import org.springframework.boot.ssl.SslBundles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException

class AppApplicationTest :
    StringSpec({
        val database = TestDatabase.migrated()
        database.role("app_runtime")
        val arguments =
            arrayOf(
                "--server.port=0",
                "--management.server.port=0",
                "--SERVER_TLS_ENABLED=false",
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
            scheme: String = "http",
            tls: SSLContext = SSLContext.getDefault(),
        ): HttpResponse<String> =
            HttpClient
                .newBuilder()
                .sslContext(tls)
                .build()
                .send(
                    HttpRequest.newBuilder(URI("$scheme://localhost:$port$path")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

        val serverCa = TestCa("Test internal CA")
        val (certificate, key) = serverCa.issue("localhost")
        val tls =
            arguments.filterNot { it == "--SERVER_TLS_ENABLED=false" }.toTypedArray() +
                arrayOf("--SERVER_TLS_CERT=$certificate", "--SERVER_TLS_KEY=$key")

        "with a certificate both ports answer over TLS alone, so the ALB checks health over TLS too" {
            runApplication<AppApplication>(*tls).use { context ->
                val main = context.environment.getProperty("local.server.port")
                val management = context.environment.getProperty("local.management.port")

                get(management, "/actuator/health", "https", serverCa.trust()).statusCode() shouldBe 200
                get(main, "/actuator/health", "https", serverCa.trust()).statusCode() shouldBe 404

                listOf(main, management).forEach { port ->
                    runCatching { get(port, "/actuator/health").statusCode() }.getOrNull() shouldNotBe 200
                }
                shouldThrow<SSLHandshakeException> { get(management, "/actuator/health", "https", TestCa("Test other CA").trust()) }
            }
        }

        "TLS without a certificate does not start" {
            shouldThrowAny { runApplication<AppApplication>(*tls.filterNot { it.startsWith("--SERVER_TLS_") }.toTypedArray()) }
        }

        "app guards TLS in the deployed profiles" {
            runApplication<AppApplication>(*arguments).use { context ->
                context.getBean(TlsGuard::class.java).shouldNotBeNull()
            }
        }

        "every exporter trusts the CA in OTLP_TLS_CA alone" {
            runApplication<AppApplication>(*arguments, *OTLP_EXPORT, "--OTLP_TLS_CA=${TestCa("Test collector CA").pem}").use { context ->
                OTLP_SSL_BUNDLES.forEach { context.environment.getProperty(it) shouldBe "otlp" }
                val bundle = context.getBean(SslBundles::class.java).getBundle("otlp")
                val store = bundle.stores.trustStore.shouldNotBeNull()
                store.aliases().toList().map { (store.getCertificate(it) as X509Certificate).subjectX500Principal.name } shouldBe
                    listOf("CN=Test collector CA")
            }
        }

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

        "traces, metrics and logs leave only for an OTLP endpoint" {
            runApplication<AppApplication>(*arguments).use { context ->
                context.getBeansOfType(SpanExporter::class.java).shouldBeEmpty()
                context.getBeansOfType(OtlpMeterRegistry::class.java).shouldBeEmpty()
                context.getBeansOfType(LogRecordExporter::class.java).shouldBeEmpty()
                context.getBeansOfType(LogExport::class.java).shouldBeEmpty()
            }
            runApplication<AppApplication>(*arguments, *OTLP_EXPORT).use { context ->
                context.getBeansOfType(SpanExporter::class.java).shouldNotBeEmpty()
                context.getBeansOfType(OtlpMeterRegistry::class.java).shouldNotBeEmpty()
                context.getBeansOfType(LogRecordExporter::class.java).shouldNotBeEmpty()
                context.getBeansOfType(LogExport::class.java).shouldNotBeEmpty()
            }
        }

        "the counter of Valkey failures leaves with the OTLP metrics" {
            runApplication<AppApplication>(*arguments, *OTLP_EXPORT).use { context ->
                context
                    .getBean(OtlpMeterRegistry::class.java)
                    .find("idp.throttle.failures")
                    .counter()
                    .shouldNotBeNull()
            }
        }
    })
