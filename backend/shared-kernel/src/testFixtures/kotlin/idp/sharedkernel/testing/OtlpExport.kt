package idp.sharedkernel.testing

/** What Spring Boot sets from `OTEL_EXPORTER_OTLP_ENDPOINT` and `OTEL_METRICS_EXPORTER=otlp`, aimed at a closed port. */
val OTLP_EXPORT =
    arrayOf(
        "--management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:1/v1/traces",
        "--management.opentelemetry.logging.export.otlp.endpoint=http://127.0.0.1:1/v1/logs",
        "--management.otlp.metrics.export.url=http://127.0.0.1:1/v1/metrics",
        "--management.otlp.metrics.export.enabled=true",
    )

/** The settings that name the SSL bundle of each exporter, `otlp` in every service. */
val OTLP_SSL_BUNDLES =
    listOf(
        "management.opentelemetry.tracing.export.otlp.ssl.bundle",
        "management.opentelemetry.logging.export.otlp.ssl.bundle",
        "management.otlp.metrics.export.ssl.bundle",
    )
