package idp.sharedkernel.web

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

private const val ENDPOINT = "https://otel-collector.idp.internal:4318"

private val DEPLOYED =
    mapOf(
        "server.ssl.enabled" to "true",
        "management.server.ssl.enabled" to "true",
        "management.opentelemetry.tracing.export.otlp.endpoint" to "$ENDPOINT/v1/traces",
        "management.opentelemetry.logging.export.otlp.endpoint" to "$ENDPOINT/v1/logs",
        "management.otlp.metrics.export.enabled" to "true",
        "management.otlp.metrics.export.url" to "$ENDPOINT/v1/metrics",
        "spring.ssl.bundle.pem.otlp.truststore.certificate" to "-----BEGIN CERTIFICATE-----",
    )

class TlsGuardTest :
    FunSpec({
        fun environment(
            profile: String,
            properties: Map<String, String?>,
        ) = StandardEnvironment().apply {
            setActiveProfiles(profile)
            val set: Map<String, Any> = properties.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
            propertySources.addFirst(MapPropertySource("test", set))
        }

        fun guard(
            profile: String = "prod",
            serves: Boolean = true,
            vararg changes: Pair<String, String?>,
        ) = TlsGuard(environment(profile, DEPLOYED + changes), serves)

        test("prod and staging start with TLS on both ports and towards the collector") {
            shouldNotThrowAny { guard("prod") }
            shouldNotThrowAny { guard("staging") }
        }

        listOf("server.ssl.enabled", "management.server.ssl.enabled").forEach { property ->
            test("$property=false is refused in prod and staging") {
                listOf("prod", "staging").forEach { profile ->
                    shouldThrow<IllegalStateException> { guard(profile, true, property to "false") }.message shouldContain
                        "SERVER_TLS_ENABLED=false is refused in the $profile profile"
                }
            }

            test("$property left out is refused, since TLS is on only where it is set") {
                shouldThrow<IllegalStateException> { guard("prod", true, property to null) }
            }
        }

        test("a service without a server, worker, needs no server TLS") {
            shouldNotThrowAny { guard("prod", false, "server.ssl.enabled" to "false", "management.server.ssl.enabled" to null) }
        }

        listOf(
            "management.opentelemetry.tracing.export.otlp.endpoint" to "http://otel-collector.idp.internal:4318/v1/traces",
            "management.opentelemetry.logging.export.otlp.endpoint" to "http://otel-collector.idp.internal:4318/v1/logs",
            "management.otlp.metrics.export.url" to "http://otel-collector.idp.internal:4318/v1/metrics",
        ).forEach { endpoint ->
            test("${endpoint.first} over plain HTTP is refused") {
                listOf(true, false).forEach { serves ->
                    shouldThrow<IllegalStateException> { guard("prod", serves, endpoint) }.message shouldContain
                        "An OTLP endpoint without TLS is refused in the prod profile"
                }
            }
        }

        test("metrics without a URL go to plain HTTP on localhost and are refused") {
            shouldThrow<IllegalStateException> { guard("prod", true, "management.otlp.metrics.export.url" to null) }
        }

        test("metrics that are off need no URL") {
            shouldNotThrowAny {
                guard("prod", true, "management.otlp.metrics.export.enabled" to "false", "management.otlp.metrics.export.url" to null)
            }
        }

        test("an endpoint without OTLP_TLS_CA is refused, the exporters would trust the JVM store") {
            listOf(null, "", " ").forEach { ca ->
                shouldThrow<IllegalStateException> {
                    guard("prod", true, "spring.ssl.bundle.pem.otlp.truststore.certificate" to ca)
                }.message shouldContain "without OTLP_TLS_CA"
            }
        }

        test("without an endpoint nothing is exported and no CA is needed") {
            shouldNotThrowAny {
                guard(
                    "prod",
                    true,
                    "management.opentelemetry.tracing.export.otlp.endpoint" to null,
                    "management.opentelemetry.logging.export.otlp.endpoint" to null,
                    "management.otlp.metrics.export.enabled" to null,
                    "spring.ssl.bundle.pem.otlp.truststore.certificate" to null,
                )
            }
        }

        test("the local profile and tests may run without TLS") {
            shouldNotThrowAny {
                TlsGuard(
                    environment(
                        "local",
                        mapOf(
                            "server.ssl.enabled" to "false",
                            "management.opentelemetry.tracing.export.otlp.endpoint" to "http://lgtm:4318/v1/traces",
                        ),
                    ),
                    true,
                )
            }
        }
    })
