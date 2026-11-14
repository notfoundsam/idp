# Portability

How the platform leaves AWS: what replaces each AWS service, what already runs without AWS, what an installation on premises still needs, and in which order an environment moves. The decision is in [ADR-001](adr/001-record-architecture-decisions.md): the services speak open protocols, and the AWS SDK stays in the `adapter.aws` packages of `shared-kernel`, which an architecture rule enforces. The first version ships no stack for on premises.

## What replaces what

| Concern | AWS | On premises | State |
|---|---|---|---|
| Services and one-off tasks | ECS Fargate | Docker Swarm or a similar orchestrator | No stack yet. The images are the same |
| Database | RDS Multi-AZ, PITR | Postgres with a synchronous replica, such as Patroni, and pgBackRest | Runs as is |
| Connection pool | PgBouncer of `infra/pgbouncer` | The same image | Runs as is |
| Limits | ElastiCache Valkey | Valkey with TLS and a password | Runs as is |
| Keys | AWS KMS | OpenBao transit | Adapter exists, its production setup does not, see below |
| Mail | SES through SMTP | Any SMTP server | Runs as is |
| Secrets | Secrets Manager, handed over as variables | Docker secrets | Needs work |
| Ingress and per-address limits | CloudFront with WAF, ALB | Traefik or HAProxy | Needs a per-address limit and a client header |
| Static files | S3 behind CloudFront | nginx with a shared volume | Runs as is, locally already |
| Erasure journal | S3 with Object Lock | An S3-compatible store with Object Lock, or a protected file | Needs work |
| Telemetry | The collector of `infra/otel-collector` into CloudWatch and X-Ray | The same collector into Loki, Tempo and Prometheus | Needs another collector config |
| Audit of the cloud | CloudTrail and the alarms of `modules/alarms` | The audit device of OpenBao and alerts on it | Needs work |
| Internal certificates | The CA of `modules/pki` | A CA of the installation | Needs work |
| Releases | `deploy.yml` and `rollback.yml` with `infra/deploy` | Scripts of their own in the same order | Needs work |

## Already without AWS

The local environment of `docker-compose.yml` runs every service with Postgres, PgBouncer, Valkey, OpenBao, an SMTP server, nginx and an OTLP backend, and no AWS. In particular:

- **Database:** the services need plain Postgres and the roles of `migrator`. The images of `infra/pgbouncer` and `infra/db-bootstrap` run against any Postgres without a superuser, as `ProductionDatabaseTest` shows.
- **Static files:** nginx serves the pages with the config that `infra/web` renders from the same headers and routes as the CloudFront Function, and `make smoke` checks it.
- **Client address:** `app` reads the address from the header in `CLIENT_IP_HEADER`, any header that the ingress overwrites. The `prod` profile refuses `X-Forwarded-For` and `Forwarded`.
- **Telemetry:** the services only export OTLP, and the alerts of `dev-tools/lgtm/alerts.yaml` are the source of the CloudWatch alarms.
- **Release checks:** the smoke test of `infra/deploy/smoke-sso.mjs` runs against any IdP, locally with `make release-smoke` and `make smoke-sso`, and so do `make check-registries`, `make sbom` and `make vulnerabilities`.
- **Release order:** the compatibility rules of [ADR-007](adr/007-release-compatibility.md) hold on any orchestrator, and the order of the steps in [infra/terraform/README.md](../infra/terraform/README.md#releases) carries over to other tools.

## What an installation still needs

- **Stack:** `docker-compose.yml` is for development, with fixed credentials, debug ports and the `local` profile. An installation needs a stack with the `prod` profile, read-only roots, TLS on every connection and one credential per service, as `modules/app` sets up the tasks.
- **Secrets:** the services read their credentials from variables, and ECS fills them from Secrets Manager. Docker secrets are files, so the services need to read them from files, for example through `spring.config.import` with `configtree:`, or an entrypoint that exports them.
- **OpenBao in production:** the adapter takes one fixed token in `OPENBAO_TOKEN` and trusts the default trust store of the JVM. It needs a CA of its own, as every other internal client has (ADR-009), and tokens that renew. Each service needs a policy of its own that matches the key policies of `modules/iam`: `app` only decrypts with the keys `tenant-*`, `ops` and the CLI only encrypt with the platform KEK, as `dev-tools/openbao/init.sh` sets up for `ops`. OpenBao deletes a key at once, so a destroyed KEK has no seven days of grace as in AWS KMS.
- **Operator identity:** outside the `local` profile the CLI names its caller through `sts:GetCallerIdentity`. It needs another `CallerIdentity` that names the operator who runs it.
- **Erasure journal:** `S3ErasureJournal` takes a region and a bucket, but no endpoint, so an S3-compatible store needs that setting. The file journal works anywhere, but nothing keeps a service from rewriting the file, which Object Lock does in AWS. Either way the journal lives apart from the database and keeps an entry for 45 days, see [restore-runbook.md](security/restore-runbook.md).
- **Ingress:** the WAF limits each address before `app` sees a request, see [limits.md](security/limits.md). The ingress takes over that limit, sets the client header and is the only path to `app`. `ops` stays reachable from the VPN alone.
- **Audit of key use:** the alarms on `Decrypt` outside the services, on key policies and on snapshots read CloudTrail. The audit device of OpenBao and the logs of the database carry the same facts on premises.
- **Releases:** the scripts of `infra/deploy` drive ECS and S3 through the AWS CLI. An installation needs the same steps for its orchestrator, with `migrator` before the services and the pages after them.

An HSM with an API of its own needs a new adapter of `KeyManagementService` with a URI scheme of its own in `KeyManagementSettings`, tested like the other two.

## Moving an environment

Move the keys first and the data after them. The key policies of tenant KEKs let only the task roles in AWS decrypt, so a service outside AWS cannot read a tenant key that AWS KMS wraps.

1. **Run OpenBao where the services in AWS reach it.** The private subnets have no route to the internet, so this takes a private link to the installation and a rule in `modules/network`.
2. **Move the tenant KEKs** as [crypto-inventory.md](security/crypto-inventory.md#moving-tenants-to-another-kms) describes, with `KMS_NEW_KEYS=hcvault` in `worker`. Every active or suspended tenant then has a `kek_uri` of `hcvault://` and the record `tenant.kek_replaced` in its journal.
3. **Switch the platform KEK** as step 6 of the same section describes.
4. **Wait for the previous KEKs.** `PreviousKekJob` destroys each in AWS KMS after `KMS_PREVIOUS_KEK_RETENTION`, 45 days. Until then a restore of an RDS backup needs both adapters, see [restore-runbook.md](security/restore-runbook.md). Then remove the AWS adapter.
5. **Copy the data.** Stop `app`, `worker` and `ops` as before a restore, copy the database with its roles and the entries of the erasure journal, set the passwords of the runtime roles as `role-passwords` does, and start the services on premises.
6. **Point the domain at the new ingress.** Keep the domain: passkeys are bound to it, and the SAML metadata of every tenant names it, so neither users nor Service Providers notice the move.

Throttle counters stay behind in Valkey, which only restarts the limits. Sessions, flows and SAML state live in Postgres and move with it.

A compromised key during the move is handled by [key-compromise-runbook.md](security/key-compromise-runbook.md), on either KMS.
