package idp.ops

import idp.sharedkernel.testing.OTLP_EXPORT
import idp.sharedkernel.testing.OTLP_SSL_BUNDLES
import idp.sharedkernel.testing.TestCa
import idp.sharedkernel.throttle.RateLimiter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.springframework.boot.runApplication
import org.springframework.boot.ssl.SslBundles
import org.springframework.core.NestedExceptionUtils
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext

class OpsApplicationTest :
    StringSpec({
        val environment = OpsEnvironment()
        val arguments = environment.arguments

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

        "health answers on the management port only" {
            runApplication<OpsApplication>(*arguments).use { context ->
                val main = context.environment.getProperty("local.server.port")
                val management = context.environment.getProperty("local.management.port")

                val health = get(management, "/actuator/health")
                health.statusCode() shouldBe 200
                health.body() shouldContain "\"status\":\"UP\""

                get(main, "/actuator/health").statusCode() shouldBe 404
            }
        }

        "the management port exposes nothing but health" {
            runApplication<OpsApplication>(*arguments).use { context ->
                val management = context.environment.getProperty("local.management.port")

                get(management, "/actuator/env").statusCode() shouldBe 404
            }
        }

        "with a certificate both ports answer over TLS alone, so the ALB checks health over TLS too" {
            runApplication<OpsApplication>(*environment.tlsArguments, "--spring.profiles.active=prod").use { context ->
                val main = context.environment.getProperty("local.server.port")
                val management = context.environment.getProperty("local.management.port")
                val trust = environment.serverCa.trust()

                get(management, "/actuator/health", "https", trust).statusCode() shouldBe 200
                get(main, "/actuator/health", "https", trust).statusCode() shouldBe 404

                listOf(main, management).forEach { port ->
                    runCatching { get(port, "/actuator/health").statusCode() }.getOrNull() shouldNotBe 200
                }
            }
        }

        "TLS without a certificate does not start" {
            shouldThrow<Exception> {
                runApplication<OpsApplication>(*arguments.filterNot { it == "--SERVER_TLS_ENABLED=false" }.toTypedArray())
            }
        }

        listOf("prod", "staging").forEach { profile ->
            "the $profile profile refuses to serve without TLS" {
                val failure = shouldThrow<Exception> { runApplication<OpsApplication>(*arguments, "--spring.profiles.active=$profile") }

                NestedExceptionUtils.getMostSpecificCause(failure).message.shouldNotBeNull() shouldContain
                    "SERVER_TLS_ENABLED=false is refused in the $profile profile"
            }
        }

        "ops creates no Valkey adapter" {
            runApplication<OpsApplication>(*arguments).use { context ->
                context.getBeansOfType(RateLimiter::class.java).shouldBeEmpty()
            }
        }

        "traces and metrics leave only for an OTLP endpoint" {
            runApplication<OpsApplication>(*arguments).use { context ->
                context.getBeansOfType(SpanExporter::class.java).shouldBeEmpty()
                context.getBeansOfType(OtlpMeterRegistry::class.java).shouldBeEmpty()
            }
            runApplication<OpsApplication>(*arguments, *OTLP_EXPORT).use { context ->
                context.getBeansOfType(SpanExporter::class.java).shouldNotBeEmpty()
                context.getBeansOfType(OtlpMeterRegistry::class.java).shouldNotBeEmpty()
            }
        }

        "every exporter trusts the CA in OTLP_TLS_CA alone" {
            runApplication<OpsApplication>(*arguments, *OTLP_EXPORT, "--OTLP_TLS_CA=${TestCa("Test collector CA").pem}").use { context ->
                OTLP_SSL_BUNDLES.forEach { context.environment.getProperty(it) shouldBe "otlp" }
                val bundle = context.getBean(SslBundles::class.java).getBundle("otlp")
                val store = bundle.stores.trustStore.shouldNotBeNull()
                store.aliases().toList().map { (store.getCertificate(it) as X509Certificate).subjectX500Principal.name } shouldBe
                    listOf("CN=Test collector CA")
            }
        }
    })
