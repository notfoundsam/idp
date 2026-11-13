# Logging

What the services log, in which form, where it goes, who reads it and how long it stays. The audit journals are records of the product and not logs, see [data-protection.md](data-protection.md). A new kind of line, a new destination or a new field goes here first.

## Destinations

Logs go to two places and nowhere else:

- **Console.** Every service writes one JSON object per line to stdout. In AWS, ECS ships it to the CloudWatch log group `/<env>/<task>`, locally `docker compose logs` shows it.
- **OTLP.** With `OTEL_EXPORTER_OTLP_ENDPOINT` set, `LogExport` adds an appender to Logback that sends each line through the logs API of OpenTelemetry to the collector, beside traces and metrics. Without the variable Spring Boot creates no exporter, and nothing leaves the process this way. Locally the collector is the one of `grafana/otel-lgtm` and stores logs in Loki.

No service writes log files. `migrator` has no exporter, its lines reach the console alone.

## Production

`app`, `worker`, `ops` and the CLI export over HTTPS to the collector of `infra/otel-collector`, an ECS service at `otel-collector.idp.internal:4318`, which they trust through the internal CA in `OTLP_TLS_CA` alone. `prod` and `staging` refuse an endpoint without TLS or without that CA (`TlsGuard`). The collector writes through the interface endpoints of the VPC:

| Signal | Store | Name |
|---|---|---|
| Lines of `idp.security` | CloudWatch Logs | `/<env>/otlp/security`, a stream per service |
| Other OTLP lines | CloudWatch Logs | `/<env>/otlp`, a stream per service |
| Metrics `idp.*` | CloudWatch metrics through the embedded metric format | Namespace `<env>`, dimension `service.name` and the tags of the metric. The EMF events stay in `/<env>/otlp/metrics` |
| Spans | X-Ray with Transaction Search | The log group `aws/spans` |
| Console of the collector | CloudWatch Logs | `/<env>/otel-collector` |

The services send metrics as deltas (`OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=delta`), as EMF takes them. Other metrics of Micrometer stop at the collector, each would cost a custom metric.

The store is apart from the services. Their task roles deny every call of CloudWatch Logs, X-Ray and CloudWatch, and their execution roles, which ECS writes the console with, deny reading, shortening and deleting lines. The collector only appends to its three groups and denies the same, and only X-Ray writes `aws/spans`. Every log group has deletion protection, so a line leaves only when its retention ends, or after somebody with rights on the account turned the protection off, which CloudTrail records.

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

Every line of a request or an event also carries `tenant_id` when it runs for a tenant and `correlation_id`. A request starts a correlation in `CorrelationFilter`, whatever the client sends, and the events it publishes keep it, so the lines of `worker` that handle them carry the same ID. Lines inside a span also carry `traceId` and `spanId`.

Personal data, passwords, codes, tokens and SAML messages never reach a line, a span or a metric: code logs types and IDs, and `Failures` drops exception texts. `UnexpectedErrorFilter` catches what a request throws before Tomcat would log it with its text. `NoPiiInLogsTest` runs logins, refusals, failures and a SAML login and finds none of them on the console, `TracingTest` checks the spans.

## Format

The console uses the ECS format of Spring Boot (`logging.structured.format.console: ecs`):

- `@timestamp` in ISO 8601 and UTC, with a `Z`.
- `log.level`, `log.logger`, `process.thread.name`, `service.name` from `spring.application.name`, `message`.
- The MDC fields as keys of their own: `tenant_id`, `correlation_id`, `traceId`, `spanId`.
- `error.type`, `error.message` and `error.stack_trace` when a library logs an exception.

The JSON encoder escapes line breaks and control characters, so a value cannot start a line of its own or forge a field. An OTLP record has the same message as its body, the MDC fields as attributes and the span as its context. `LogsTest` checks both.

## Traces

`app` consumes no trace context from requests (`management.tracing.propagation.consume` is empty): it faces the internet, and a client that sends `traceparent` would choose the trace IDs and, with the parent-based sampler, which requests are sampled. Every request of `app` starts its own trace, and the sampler keeps 10% of them by the default probability of Spring Boot. `ops` sits behind the VPN and `worker` takes no requests, so both keep the defaults. Locally `OTEL_TRACES_SAMPLER=parentbased_always_on` samples everything.

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

Metrics carry no tenant: hundreds of tenants would make a series each. `dev-tools/lgtm` provisions the dashboard and these alerts into the local Grafana. In AWS `modules/alarms` turns them into CloudWatch alarms on the namespace `<env>` with the same thresholds, which a load test on staging replaces with measured ones, and notifies the on-call engineers through SNS. The refusals of Throttle are counted by a metric filter on `/<env>/otlp/security`.

## Audit of AWS

A CloudTrail trail records the management events of the account in every region into `/<env>/cloudtrail` and a bucket of its own, both kept a year. The log group has deletion protection, the trail only appends to it, and the bucket refuses deletes to everybody. Alarms on the trail fire on `Decrypt` by anybody but the task roles of `app`, `worker` and `recovery`, on key deletion outside `worker` and `recovery`, on a replaced key policy, on copying, sharing or exporting an RDS snapshot, on any call of the root user, on changes of IAM, and on changes of the trail, a log group, its retention or an alarm. `infra/terraform/README.md` lists them.

## Retention and access

| Data | Local | Production | Who reads it |
|---|---|---|---|
| Console lines | Until the container is recreated | 90 days | The on-call engineers of the platform, read only |
| OTLP logs | In the `idp-lgtm` volume until `make clean` | 90 days, lines of `idp.security` 1 year | The same, read only |
| Traces | As above | 14 days | The same |
| Metrics | As above | 15 months, the fixed retention of CloudWatch, at least the 13 an investigation needs | The same |
| CloudTrail events | None | 1 year | The same |

Nobody changes or deletes a stored line before its time, the store only expires them, see [Production](#production). The on-call engineers read through the policy `<env>-telemetry-read` of `modules/telemetry`, which reads and changes nothing else, and no other principal gets it. X-Ray also indexes 1% of the spans as trace summaries, which it keeps 30 days: they hold the same IDs and names as the spans. Admins of a tenant never see logs: their journal is the audit of their tenant. Logs hold IDs and no personal data, so the periods follow what an investigation needs and not an erasure.
