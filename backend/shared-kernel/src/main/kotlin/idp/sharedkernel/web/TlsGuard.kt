package idp.sharedkernel.web

import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles

private val DEPLOYED = listOf("prod", "staging")
private val SERVERS = listOf("server.ssl.enabled", "management.server.ssl.enabled")
private const val METRICS_ENABLED = "management.otlp.metrics.export.enabled"

/** Where Micrometer sends metrics when the export is on and no URL is set. */
private const val METRICS_DEFAULT_URL = "http://localhost:4318/v1/metrics"
private const val OTLP_CA = "spring.ssl.bundle.pem.otlp.truststore.certificate"

/**
 * In `prod` and `staging` a service answers its load balancer only over TLS and exports telemetry only over TLS to a collector
 * that `OTLP_TLS_CA` certifies. See docs/security/communications.md.
 */
class TlsGuard(
    environment: Environment,
    serves: Boolean,
) {
    init {
        DEPLOYED.firstOrNull { environment.acceptsProfiles(Profiles.of(it)) }?.let { profile ->
            if (serves) {
                SERVERS.forEach { property ->
                    check(environment.getProperty(property, Boolean::class.java, false)) {
                        "SERVER_TLS_ENABLED=false is refused in the $profile profile, $property is off"
                    }
                }
            }
            val endpoints = endpoints(environment)
            check(endpoints.all { it.startsWith("https://") }) { "An OTLP endpoint without TLS is refused in the $profile profile" }
            check(endpoints.isEmpty() || !environment.getProperty(OTLP_CA).isNullOrBlank()) {
                "An OTLP endpoint without OTLP_TLS_CA is refused in the $profile profile, the exporters would trust the public CAs"
            }
        }
    }

    private fun endpoints(environment: Environment): List<String> {
        val traces = environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint")
        val logs = environment.getProperty("management.opentelemetry.logging.export.otlp.endpoint")
        val metrics =
            if (environment.getProperty(METRICS_ENABLED, Boolean::class.java, false)) {
                environment.getProperty("management.otlp.metrics.export.url") ?: METRICS_DEFAULT_URL
            } else {
                null
            }
        return listOfNotNull(traces, logs, metrics).filter { it.isNotBlank() }
    }
}
