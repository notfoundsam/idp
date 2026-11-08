# AWS infrastructure

Terraform for staging and production. Each environment lives in an AWS account of its own.

| Path | Holds |
|---|---|
| `bootstrap/` | The state bucket with its KMS key, the GitHub OIDC provider and the roles GitHub Actions assume. Applied once per account by hand |
| `environments/<env>/` | The root of an environment: network, ECR, static bucket, ECS cluster, internal CA, database and PgBouncer, Valkey, keys, erasure journal and mail. The output `services` holds what each service gets beside the database |
| `modules/network` | VPC without a route to the internet, AWS services through VPC endpoints, a security group per workload, the public ALB |
| `modules/registry` | ECR repositories with immutable tags |
| `modules/static` | The bucket of the frontend files, read only by CloudFront |
| `modules/cluster` | The ECS cluster and the private DNS namespace `idp.internal` |
| `modules/pki` | The internal CA and the server certificates it issues, their keys in Secrets Manager |
| `modules/data` | RDS, the role passwords, the bootstrap tasks and PgBouncer, see `infra/pgbouncer` and `infra/db-bootstrap` |
| `modules/valkey` | ElastiCache Valkey with TLS and an AUTH token, reachable from `app` alone |
| `modules/keys` | The platform KEK and the HMAC key of Throttle |
| `modules/erasure-journal` | The bucket of the erasure journal under Object Lock, see `docs/security/restore-runbook.md` |
| `modules/mail` | The SMTP credentials of SES for one sender |

The region and the domain are variables, and only the `*.example` files hold placeholders. Both must be chosen before the first customer: passkeys are bound to the domain, so changing it later locks every user out.

`make lint-infra` checks formatting, validates every root without its backend and runs TFLint. It reads no state and calls no AWS API.

## Setting up an environment

1. In the account of the environment, apply `bootstrap/` with a copy of `terraform.tfvars.example`. Its state stays local: it holds no secret, and every resource in it can be imported again.
2. In GitHub, create the environments `<env>-infra` for the `terraform` role and `<env>` for the `deploy` role, both with required reviewers. A token of one environment cannot assume the role of the other.
3. Write `environments/<env>/backend.hcl` from `backend.hcl.example` with the outputs of the bootstrap, and `terraform.tfvars` from its example. Then `terraform -chdir=environments/<env> init -backend-config=backend.hcl`.
4. Build `infra/pgbouncer` and `infra/db-bootstrap`, push both to ECR under `infra_image_tag`, and apply the environment.
5. Bootstrap the database, below.

## Database bootstrap

RDS has no superuser, so `idp_owner` is created by the master user, whose password RDS keeps in a secret that only the bootstrap task may read. Run each step as a one-off task with the network from the output `bootstrap_network`:

```bash
aws ecs run-task --cluster <cluster_name> --launch-type FARGATE \
  --task-definition <bootstrap_task_definitions.owner> \
  --network-configuration "awsvpcConfiguration={subnets=[...],securityGroups=[...],assignPublicIp=DISABLED}"
```

1. `owner`, once on a new instance: creates `idp_owner` and `provisioner` with their passwords from Secrets Manager and hands the database to `idp_owner`, as `dev-tools/postgres/init.sql` does locally.
2. `migrator`, which creates the runtime roles.
3. `role-passwords`, as `idp_owner`: sets the passwords of `app_runtime`, `worker_runtime` and `ops_runtime` from Secrets Manager.

PgBouncer starts before that, and its connections fail until step 3 is done. Every step is safe to repeat.

## Credentials

Terraform generates every credential of the services into Secrets Manager, and nothing of `dev-tools` reaches an environment. The prod profile of `app` refuses Valkey without TLS or a password and an HMAC key shorter than 32 characters, so the local values cannot start it.

| Credential | Secret | Replaced by raising |
|---|---|---|
| Database role passwords | `<env>/db/<role>` | `db_password_versions.<role>` |
| AUTH token of Valkey, `VALKEY_PASSWORD` | `<env>/valkey/auth-token` | `credential_versions.valkey_auth_token` |
| HMAC key of Throttle, `THROTTLE_IP_HMAC_KEY` | `<env>/app/throttle-ip-hmac-key` | `credential_versions.throttle_ip_hmac_key` |
| SMTP credentials of SES, `MAIL_USERNAME` and `MAIL_PASSWORD` | `<env>/mail/smtp`, keys `username` and `password` | `credential_versions.smtp` |
| TLS key of PgBouncer | `<env>/tls/pgbouncer` | An apply in the last 60 days of its certificate |

The passwords, the token and the HMAC key are write-only values, so they reach neither the plan, the state nor the repository. SES derives SMTP credentials only from the access key of an IAM user, which AWS cannot create write-only, so that key lives in the encrypted state like the keys of `modules/pki`. `ProductionDatabaseTest` runs the database images and connection settings against a Postgres that behaves like RDS, `ValkeyTlsTest` the client of `app` against a Valkey that serves TLS alone.

To replace a database password, raise its version and apply. Then run `owner` for `idp_owner` and `provisioner` or `role-passwords` for a runtime role, and redeploy PgBouncer and the services that use the role. Connections that PgBouncer opened before keep working until they close.

To replace another credential, raise its version, apply and redeploy the service that reads it:

- **Valkey token:** ElastiCache accepts the new token beside the previous one, so `app` keeps working while it redeploys. A leaked token stays valid until the next replacement, so raise the version twice, with a redeploy of `app` in between.
- **HMAC key:** the counters start over, nothing else depends on the key.
- **SMTP credentials:** the old access key is deleted in the same apply, so redeploy `worker` right after it. Until then sends fail and the outbox retries them.

## Platform KEK and erasure journal

The platform KEK lives in KMS as `alias/<env>-platform` and rotates every 365 days, as `AwsKeyManagementService` rotates the KEKs of tenants. `worker`, `ops` and `migrator` get its URI as `PLATFORM_KEK_URI`.

The erasure journal is the bucket `<env>-erasures-<account>` with versioning and Object Lock in COMPLIANCE mode, which `worker` and `migrator` get as `ERASURE_JOURNAL_BUCKET` and `ERASURE_JOURNAL_REGION`. Every version is locked for the backup retention of RDS plus 15 days, 45 in all, and the lifecycle removes it after that. The bucket policy refuses deletes to everybody, so no role can hide an entry behind a delete marker.

## TLS to Postgres

Every client verifies the server certificate and its name, `sslmode=verify-full`, and trusts one CA alone, never the public ones:

| Connection | Host | Trusted CA |
|---|---|---|
| `app`, `worker` and `ops` to PgBouncer | `pgbouncer.idp.internal` | The internal CA of `modules/pki` |
| PgBouncer to RDS | The RDS endpoint | The RDS roots of the region |
| `migrator`, the provisioner in `worker` and the bootstrap tasks to RDS | The RDS endpoint | The RDS root of `ca_cert_identifier` |

The JDBC URLs in the output `database` read the certificate from a variable: `DB_TLS_CA` for `DB_URL`, `PROVISIONER_DB_TLS_CA` for `PROVISIONER_DB_URL`. PgBouncer accepts only TLS clients, and RDS only TLS connections through `rds.force_ssl`.

The internal CA is a key in the Terraform state, which its own KMS key encrypts and only the `terraform` role reads. An apply in the last 60 days of a server certificate issues a new one, and the CA itself lives ten years.

## TLS to Valkey

ElastiCache accepts only TLS connections with the AUTH token, `transit_encryption_mode = "required"`. `app` connects to `VALKEY_URL`, a `rediss://` URL of the primary endpoint, verifies the certificate and its name, offers only TLS 1.2 and 1.3, and trusts only the certificates in `VALKEY_TLS_CA`: the roots of Amazon Trust Services, which `modules/valkey` downloads and checks on every plan. A `rediss://` URL without them does not start, and `verifyPeer` in the URL cannot turn the check off.
