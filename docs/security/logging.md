# Logging

What the services log, in which form, where it goes, who reads it and how long it stays. The audit journals are records of the product
and not logs, see [data-protection.md](data-protection.md). A new kind of line, a new destination or a new field goes here first.

## Destinations

Logs go to two places and nowhere else:

- **Console.** Every service writes one JSON object per line to stdout. In production the container runtime ships it to the log store
  of the platform, locally `docker compose logs` shows it.
- **OTLP.** With `OTEL_EXPORTER_OTLP_ENDPOINT` set, `LogExport` adds an appender to Logback that sends each line through the logs API
  of OpenTelemetry to the collector, beside traces and metrics. Without the variable Spring Boot creates no exporter, and nothing
  leaves the process this way. Locally the collector is the one of `grafana/otel-lgtm` and stores logs in Loki.

No service writes log files. The collector of production and its store are set up in task 41, and only they receive the OTLP lines.

## Inventory

| Layer | Logger | What a line holds |
|---|---|---|
| Bus | `LoggerDecorator` | The message type, the outcome as `OK` or an exception class, the duration and the correlation. Never the message |
| Security events | `idp.security` | `event=throttle.rejected` with the scope, the dimension and the tenant. `event=saml.request_rejected` with the reason and the tenant. `event=ops.login_refused` with the reason of Spring Security |
| Failures | `Failures` | `event=failure`, where it surfaced (`http`, `outbox`, `job`), whether TLS failed, and the class and frames of each cause. Never an exception text |
| Outbox | `OutboxDispatcher`, `IdempotentHandler` | The event ID and the attempt, a dead event, a skipped handler of a deleted tenant |
| Jobs | `JobScheduler`, `TenantJobRunner`, the key jobs | The job, the tenant, a lost lease |
| Mail | `MailDispatcher` | The template and the user ID, never the address |
| Throttle | `ValkeyRateLimiter` | That limits are off and the class of the Valkey failure |
| Migrator | `ReapplyErasuresJob` | How many tenants and users it erased again |
| Libraries | Spring, Tomcat, Hikari, Flyway, OpenSAML | Start, stop and pool state at `INFO`. No access log: the load balancer and the edge keep those |

Every line of a request or an event also carries `tenant_id` when it runs for a tenant and `correlation_id`. A request starts a
correlation in `CorrelationFilter`, whatever the client sends, and the events it publishes keep it, so the lines of `worker` that
handle them carry the same ID. Lines inside a span also carry `traceId` and `spanId`.

Personal data, passwords, codes, tokens and SAML messages never reach a line, a span or a metric: code logs types and IDs, and
`Failures` drops exception texts. `UnexpectedErrorFilter` catches what a request throws before Tomcat would log it with its text.
`NoPiiInLogsTest` runs logins, refusals, failures and a SAML login and finds none of them on the console, `TracingTest` checks the
spans.

## Format

The console uses the ECS format of Spring Boot (`logging.structured.format.console: ecs`):

- `@timestamp` in ISO 8601 and UTC, with a `Z`.
- `log.level`, `log.logger`, `process.thread.name`, `service.name` from `spring.application.name`, `message`.
- The MDC fields as keys of their own: `tenant_id`, `correlation_id`, `traceId`, `spanId`.
- `error.type`, `error.message` and `error.stack_trace` when a library logs an exception.

The JSON encoder escapes line breaks and control characters, so a value cannot start a line of its own or forge a field. An OTLP
record has the same message as its body, the MDC fields as attributes and the span as its context. `LogsTest` checks both.

## Traces

`app` consumes no trace context from requests (`management.tracing.propagation.consume` is empty): it faces the internet, and a
client that sends `traceparent` would choose the trace IDs and, with the parent-based sampler, which requests are sampled. Every
request of `app` starts its own trace, and the sampler keeps 10% of them by the default probability of Spring Boot. `ops` sits
behind the VPN and `worker` takes no requests, so both keep the defaults. Locally `OTEL_TRACES_SAMPLER=parentbased_always_on`
samples everything.

## Metrics and alerts

| Metric | Meaning | Alert |
|---|---|---|
| `idp.login.failures` by `factor` | A refused login step, the same refusals the journal records as `login.failed` | More than 100 a minute for 5 minutes |
| `idp.security` lines with `event=throttle.rejected` | Refusals of Throttle, counted from the logs | More than 300 in 5 minutes |
| `idp.throttle.failures` | A failed call to Valkey, while limits are off | Any |
| `idp.failures` by `source` and `tls` | A failure no code handles | Any. With `tls=true` as a critical one of its own |
| `idp.outbox.lag` | How long the most overdue pending event has waited past its `available_at`. An event planned for later, as offboarding plans one 30 days ahead, does not count | Over 5 minutes, or no value |
| `idp.outbox.dead` | Events that ran out of attempts | Any |
| `idp.saml.certificate.expiry` | The earliest end of a published signing certificate, in seconds since the epoch, refreshed hourly by `worker` | Less than 30 days left |

Metrics carry no tenant: hundreds of tenants would make a series each. `dev-tools/lgtm` provisions the dashboard and these alerts
into the local Grafana. Task 41 turns them into the alarms of production, where the thresholds are set from staging.

## Retention and access

| Data | Local | Production | Who reads it |
|---|---|---|---|
| Console lines | Until the container is recreated | 90 days | The on-call engineers of the platform, read only |
| OTLP logs | In the `idp-lgtm` volume until `make clean` | 90 days, lines of `idp.security` 1 year | The same, read only |
| Traces | As above | 14 days | The same |
| Metrics | As above | 13 months | The same |

Nobody changes or deletes a stored line before its time, the store only expires them. Admins of a tenant never see logs: their
journal is the audit of their tenant. Logs hold IDs and no personal data, so the periods follow what an investigation needs and not
an erasure.
