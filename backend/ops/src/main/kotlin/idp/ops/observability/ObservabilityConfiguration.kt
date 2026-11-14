package idp.ops.observability

import idp.sharedkernel.observability.CorrelationFilter
import idp.sharedkernel.observability.Failures
import idp.sharedkernel.observability.LogExport
import idp.sharedkernel.observability.UnexpectedErrorFilter
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {
    @Bean
    fun failures(registry: MeterRegistry) = Failures(registry)

    @Bean
    fun correlationFilter() = CorrelationFilter()

    @Bean
    fun unexpectedErrorFilter(failures: Failures) = UnexpectedErrorFilter(failures)

    /** Spring Boot creates an exporter only for an OTLP endpoint, so without one the lines stay on the console. */
    @Bean
    fun logExport(
        openTelemetry: OpenTelemetry,
        exporters: ObjectProvider<LogRecordExporter>,
    ): LogExport? = if (exporters.stream().findAny().isPresent) LogExport(openTelemetry) else null
}
