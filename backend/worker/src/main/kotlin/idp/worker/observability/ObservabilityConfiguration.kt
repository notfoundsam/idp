package idp.worker.observability

import idp.federation.application.FederationService
import idp.sharedkernel.observability.Failures
import idp.sharedkernel.observability.LogExport
import idp.sharedkernel.outbox.OutboxMetrics
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.web.TlsGuard
import idp.tenancy.application.TenancyService
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {
    /** worker answers only the health check of its own container, so only its export needs TLS. */
    @Bean
    fun tlsGuard(environment: Environment) = TlsGuard(environment, serves = false)

    @Bean
    fun failures(registry: MeterRegistry) = Failures(registry)

    @Bean
    fun outboxMetrics(platform: PlatformTransaction) = OutboxMetrics(platform)

    /** Declared as itself, so Spring Boot binds it as a `MeterBinder` and `worker` runs it as a `ScheduledJob`. */
    @Bean
    fun certificateExpiryJob(
        tenancy: TenancyService,
        federation: FederationService,
        failures: Failures,
    ) = CertificateExpiryJob(tenancy, federation, failures)

    /** Spring Boot creates an exporter only for an OTLP endpoint, so without one the lines stay on the console. */
    @Bean
    fun logExport(
        openTelemetry: OpenTelemetry,
        exporters: ObjectProvider<LogRecordExporter>,
    ): LogExport? = if (exporters.stream().findAny().isPresent) LogExport(openTelemetry) else null
}
