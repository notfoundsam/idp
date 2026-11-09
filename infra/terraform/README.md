# AWS infrastructure

Terraform for staging and production. Each environment lives in an AWS account of its own.

| Path | Holds |
|---|---|
| `bootstrap/` | The state bucket with its KMS key, the GitHub OIDC provider and the roles GitHub Actions assume. Applied once per account by hand |
| `environments/<env>/` | The root of an environment: network, ECR, static bucket, ECS cluster, internal CA, database and PgBouncer, Valkey, keys, erasure journal, mail and the services. The output `services` holds what each service gets beside the database |
| `modules/network` | VPC without a route to the internet, AWS services through VPC endpoints, a security group per workload, the public ALB and the internal one of `ops` |
| `modules/app` | The services `app`, `worker` and `ops`, the one-off tasks `migrator`, `recovery` and `cli`, their execution roles, the rules of `app` on the public listener and the listener of `ops` on the internal ALB |
| `modules/iam` | The task role of each task and the key policies of the platform KEK and of tenant KEKs |
| `modules/registry` | ECR repositories with immutable tags |
| `modules/static` | The bucket of the frontend files, read only by CloudFront |
| `modules/edge` | The distribution of the tenant domains `*.<domain>` with the function of `infra/web/spa-rewrite.js` for the routes of the apps, the headers of `infra/web/security-headers.json` and the cache policies of chunks and pages in front of the bucket, and `/api/*` and `/saml/*` uncached to the ALB. Its WAF, the certificates of both, the HTTPS listener of the ALB and the ingress of the ALB from CloudFront alone |
| `modules/cluster` | The ECS cluster and the private DNS namespace `idp.internal` |
| `modules/pki` | The internal CA and the server certificates it issues, their keys in Secrets Manager |
| `modules/data` | RDS, the role passwords, the bootstrap tasks and PgBouncer, see `infra/pgbouncer` and `infra/db-bootstrap` |
| `modules/valkey` | ElastiCache Valkey with TLS and an AUTH token, reachable from `app` alone |
| `modules/keys` | The platform KEK, the HMAC key of Throttle and the origin header of CloudFront |
| `modules/erasure-journal` | The bucket of the erasure journal under Object Lock, see `docs/security/restore-runbook.md` |
| `modules/mail` | The SMTP credentials of SES for one sender |

The region and the domain are variables, and only the `*.example` files hold placeholders. Both must be chosen before the first customer: passkeys are bound to the domain, so changing it later locks every user out.

`make lint-infra` checks formatting, validates every root without its backend and runs TFLint. `make test-infra` runs `terraform test` of the modules against a mocked provider. Neither reads state or calls an AWS API.

## Setting up an environment

1. In the account of the environment, apply `bootstrap/` with a copy of `terraform.tfvars.example`. Its state stays local: it holds no secret, and every resource in it can be imported again.
2. In GitHub, create the environments `<env>-infra` for the `terraform` role and `<env>` for the `deploy` role, both with required reviewers. A token of one environment cannot assume the role of the other.
3. Write `environments/<env>/backend.hcl` from `backend.hcl.example` with the outputs of the bootstrap, and `terraform.tfvars` from its example. Then `terraform -chdir=environments/<env> init -backend-config=backend.hcl`.
4. Build `infra/pgbouncer` and `infra/db-bootstrap`, push both to ECR under `infra_image_tag`, and apply the environment.
5. Bootstrap the database, below.

## Edge

Every tenant domain is a subdomain of `domain` and reaches one CloudFront distribution:

| Path | Origin | Cache | Plain HTTP |
|---|---|---|---|
| `/login/assets/*`, `/console/assets/*` | S3 | A year | Redirect |
| `/api/*`, `/saml/*` | The ALB, with every header, cookie and the query string of the viewer | None | Refused |
| Everything else | S3 behind the function: `/` leads to `/console/`, pages get their `index.html`, other paths a `404` | None | Redirect |

`app` selects the tenant by the `Host`, so CloudFront passes it on, and the certificate of the ALB matches it. The edge caches no error either: the cache key holds no `Host`, and an error of one tenant would reach the others. A missing file in S3 is therefore a `403`, as CloudFront may not list the bucket, and the error pages of a distribution cannot turn it into a `404` without replacing the errors of the API too.

CloudFront takes certificates and web ACLs only from `us-east-1`, so the environment has a second provider `aws.us_east_1`.

Nothing reaches the ALB past CloudFront and its WAF:

- **WAF:** the web ACL of the distribution limits each client address, see `docs/security/limits.md`. Behind CloudFront every request would come from an edge server, so only the edge can count by the viewer.
- **Security group:** the ALB takes HTTPS only from the managed prefix list `com.amazonaws.global.cloudfront.origin-facing`. It weighs 55 of the 60 inbound rules a security group takes by default, so the group of the ALB gets no other ingress.
- **Origin header:** the prefix list admits the distributions of every AWS account, so CloudFront also sends `X-Origin-Verify` with a generated value, and the listener answers `403` without it. Every rule that forwards to a service has to require it too, with the output `origin_header` of the module as the name and `module.keys.origin_header_value` as the value. A request with the header that no rule takes gets `404`.
- **Client address:** the origin request policy of `app` adds `CloudFront-Viewer-Address`, which CloudFront overwrites when a viewer sends it, and the output `services` hands `app` its name as `CLIENT_IP_HEADER`. `app` reads the address from that header alone and never from `X-Forwarded-For`, which keeps what the client sent.

The plan cannot prove that CloudFront overwrites the address a client sends, so a new environment is checked once: from one client, start 31 flows in a minute, each with another forged `CloudFront-Viewer-Address`. The last one must get `429`, as all of them count under the real address of the client.

DNS hosting is not decided, so Terraform creates no records. On the first apply:

1. Create the certificates alone: `terraform apply -target=module.edge.aws_acm_certificate.cloudfront -target=module.edge.aws_acm_certificate.alb`.
2. Create the records of the output `certificate_validation_records` at the DNS host and keep them, ACM renews through them.
3. Apply everything. The apply waits until ACM issues both certificates.
4. Point `*.<domain>` at the output `distribution_domain_name`.

## Services

Every task runs the image `<repository>:<release>` with a read-only root. The JVM keeps its temporary files in a volume at `/run/idp`, which ECS fills from the image.

| Task | Runs as | Reached by | Database |
|---|---|---|---|
| `app` | ECS service | The public ALB: `/api/*` and `/saml/*` with the origin header, on 8080 | `app_runtime` through PgBouncer |
| `worker` | ECS service | Nobody. It sends mail to SES on 587 through the interface endpoint | `worker_runtime` through PgBouncer, `provisioner` directly |
| `ops` | ECS service | The internal ALB at `ops.<domain>`, on 8080 | `ops_runtime` through PgBouncer |
| `migrator` | One-off task, output `one_off_tasks` | Nobody | `idp_owner` directly |
| `recovery` | One-off task of the `migrator` image with `REAPPLY_ERASURES=true`, only after a restore, see `docs/security/restore-runbook.md` | Nobody | `idp_owner` directly |
| `cli` | One-off task of the `ops` image, output `one_off_tasks` | Nobody | `ops_runtime` through PgBouncer |

The ALBs check the health of `app` and `ops` on the management port 8081, which only they reach. `worker` checks its own readiness. Staging runs the Spring profile `staging`, production `prod`: both refuse what the local environment allows, and only `staging` lets a load test set `THROTTLE_ENABLED=false` on `app`. `modules/app` refuses the profile `local`, a profile set through the output `services` and any JDWP option.

`ops` answers only on the internal ALB, whose security group admits HTTPS from `ops_client_cidrs`, the private ranges of the VPN, and nothing while the list is empty. Create the record `ops.<domain>` towards the output `ops_alb_dns_name`, beside the wildcard of the edge: it resolves to private addresses. The service tenant `ops-sso` stays a tenant domain behind CloudFront. The ALB keeps a browser on one task of `ops` for 30 minutes, as `ops` keeps its sessions in memory, and `ops` has no `CLIENT_IP_HEADER`: no header of the ALB carries an address that a client cannot forge, so it records the address of the ALB.

`ops` trusts the SAML metadata of `ops-sso` in the secret of the output `idp_metadata_secret_arn`, and a first container of its task writes it to the file of `IDP_METADATA_FILE`. Terraform creates the secret empty, so `ops` starts only once the operator has stored the metadata:

1. Run the CLI `bootstrap`, below, and wait until the service tenant is active.
2. Store its metadata: `aws secretsmanager put-secret-value --secret-id <idp_metadata_secret_arn> --secret-string file://metadata.xml`, with the file from `https://ops-sso.<domain>/saml/metadata`.
3. Start `ops` again: `aws ecs update-service --cluster <cluster_name> --service ops --force-new-deployment`.

After a key rotation at the service tenant, store the new metadata and redeploy `ops` between starting and completing the rotation.

A one-off task runs with its network from the output `one_off_tasks` and its arguments in `overrides`:

```bash
aws ecs run-task --cluster <cluster_name> --launch-type FARGATE \
  --task-definition <one_off_tasks.cli.task_definition> \
  --network-configuration "awsvpcConfiguration={subnets=[...],securityGroups=[...],assignPublicIp=DISABLED}" \
  --overrides '{"containerOverrides":[{"name":"cli","command":["cli","suspend","<tenant-id>"]}]}'
```

## Rights in AWS

Each task runs as a task role of its own from `modules/iam`, apart from the execution role with which ECS pulls the image and reads the secrets of that task alone. The output `task_role_arns` names them, and so does CloudTrail for every call of a service.

| Task | Platform KEK | Tenant KEKs | Erasure journal | Else |
|---|---|---|---|---|
| `app` | | `Decrypt` | | |
| `worker` | `Decrypt` | `CreateKey` and `EnableKeyRotation` while provisioning or moving, `Encrypt`, `Decrypt`, aliases, `ScheduleKeyDeletion` at offboarding | `PutObject` | |
| `ops` | `Encrypt` | | | |
| `cli` | `Encrypt` | | | `GetCallerIdentity`, which AWS answers to anybody |
| `migrator` | | | | |
| `recovery` | | `Decrypt`, `DeleteAlias`, `ScheduleKeyDeletion` | `ListBucket` | |

A tenant KEK appears at runtime, so no IAM policy can name it. `worker` attaches the policy of the output `tenant_kek_policy` of `modules/iam` to every key it creates, as `KMS_AWS_KEY_POLICY`, and that policy grants the rights in the table. IAM holds only the calls that name no key and those on the aliases `alias/tenant-*`. A change to the policy reaches only keys created afterwards, so older keys need `PutKeyPolicy` one by one. The platform KEK exists before the services, so IAM grants its rights by its ARN.

The key policies let the account grant the rest through IAM, as the default policy does, and deny for everybody, the root user included:

- `ops` and `cli` everything on a tenant KEK and everything but `Encrypt` on the platform KEK, so they store a contact and never read one.
- `Decrypt` of a tenant KEK beside `app`, `worker` and `recovery`, of the platform KEK beside `worker`.
- `ScheduleKeyDeletion` of a tenant KEK beside `worker` and `recovery`, of the platform KEK to every task.
- `ReEncrypt*` and `CreateGrant` on both.

They match principals by `aws:PrincipalArn`, which still matches a role created again under the same name, where a principal in the policy would stop matching. Changing a key policy stays possible for the account and lands in CloudTrail.

Every task role denies `ecs:ExecuteCommand`, and `ops` and `cli` deny every call of Secrets Manager, so neither opens a shell in `app` or reads the password of `app_runtime`, whatever is granted later. `migrator` runs every release with no rights in AWS: only `recovery` may destroy KEKs and read the journal, and `modules/app` refuses `REAPPLY_ERASURES` on any other task.

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
| Origin header of CloudFront, `X-Origin-Verify` | `<env>/edge/origin-header` | `credential_versions.origin_header` |
| TLS key of PgBouncer | `<env>/tls/pgbouncer` | An apply in the last 60 days of its certificate |

The passwords, the token and the HMAC key are write-only values, so they reach neither the plan, the state nor the repository. The origin header is written the same way, but CloudFront and the ALB keep it in their configuration, so Terraform reads it back and it lives in the encrypted state, marked sensitive in the plan. SES derives SMTP credentials only from the access key of an IAM user, which AWS cannot create write-only, so that key lives in the encrypted state like the keys of `modules/pki`. `ProductionDatabaseTest` runs the database images and connection settings against a Postgres that behaves like RDS, `ValkeyTlsTest` the client of `app` against a Valkey that serves TLS alone.

To replace a database password, raise its version and apply. Then run `owner` for `idp_owner` and `provisioner` or `role-passwords` for a runtime role, and redeploy PgBouncer and the services that use the role. Connections that PgBouncer opened before keep working until they close.

To replace another credential, raise its version, apply and redeploy the service that reads it:

- **Valkey token:** ElastiCache accepts the new token beside the previous one, so `app` keeps working while it redeploys. A leaked token stays valid until the next replacement, so raise the version twice, with a redeploy of `app` in between.
- **HMAC key:** the counters start over, nothing else depends on the key.
- **SMTP credentials:** the old access key is deleted in the same apply, so redeploy `worker` right after it. Until then sends fail and the outbox retries them.
- **Origin header:** the listener takes the new value at once, while CloudFront needs some minutes to deploy it to every edge, and the ALB refuses the API and SAML with `403` meanwhile. Nothing redeploys, but replace it only after a leak and outside working hours.

## Platform KEK and erasure journal

The platform KEK lives in KMS as `alias/<env>-platform` and rotates every 365 days, as `AwsKeyManagementService` rotates the KEKs of tenants. `worker`, `ops` and `migrator` get its URI as `PLATFORM_KEK_URI`, and its key policy comes from `modules/iam`.

The erasure journal is the bucket `<env>-erasures-<account>` with versioning and Object Lock in COMPLIANCE mode, which `worker`, `migrator` and `recovery` get as `ERASURE_JOURNAL_BUCKET` and `ERASURE_JOURNAL_REGION`. Every version is locked for the backup retention of RDS plus 15 days, 45 in all, and the lifecycle removes it after that. The bucket policy refuses deletes to everybody, so no role can hide an entry behind a delete marker.

A KEK that a move to another KMS replaced opens the backups taken before the move, so `worker` keeps it for as long as the journal keeps an entry, `KMS_PREVIOUS_KEK_RETENTION`, 45 days. Both answer how long a restore may need what existed before it, and the tenant key under both KEKs is the same, so the longer wait discloses nothing new.

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
