# Communications

Every connection of the production environment, who opens it, how it is protected and what the client trusts, and the external services the application relies on. A new connection or a new external service goes here first, together with its egress rule in `infra/terraform`. Locally the same services talk without TLS where `docker-compose.yml` says so, see [Local](#local).

## Rules

- The private subnets have no route to the internet. A task reaches only the security groups its rules name and AWS through the interface endpoints of `interface_endpoints` and the gateway endpoint of S3 (`modules/network`).
- Every connection is TLS 1.2 or 1.3. A client of an internal host verifies the certificate and its name and trusts one issuer alone: the internal CA of `modules/pki`, the RDS roots of the region or the roots of Amazon Trust Services. The images carry no trust store of their own.
- No user sets the address of a connection the services open, see [Addresses a user gives](#addresses-a-user-gives).

## Into the environment

| From | To | Protocol | Protection |
|---|---|---|---|
| Browsers and SPs | CloudFront at `*.<domain>` | HTTPS | ACM certificate of `us-east-1`, `TLSv1.2_2021`, the WAF with limits per viewer address, see [limits.md](limits.md) |
| CloudFront | The static bucket | HTTPS | Origin access control, the bucket policy admits only the distribution |
| CloudFront | Public ALB, `/api/*` and `/saml/*` | HTTPS with the `Host` of the viewer | The prefix list of CloudFront in the security group, the generated `X-Origin-Verify` on every rule, `CloudFront-Viewer-Address` as the one client address |
| Public ALB | `app` on 8080, its health on 8081 | HTTPS | Certificate of the internal CA for `app.idp.internal`. The ALB checks the certificate of no target, an AWS limit, so only its security group reaches the ports |
| VPN clients in `ops_client_cidrs` | Internal ALB at `ops.<domain>` on 443 | HTTPS | `ELBSecurityPolicy-TLS13-1-2-Res-2021-06`, the certificate of `*.<domain>`, no other ingress |
| Internal ALB | `ops` on 8080, its health on 8081 | HTTPS | As for `app`, with the certificate for `ops.idp.internal` |

`ops` has no `CLIENT_IP_HEADER`. The internal ALB adds `X-Forwarded-For`, which keeps what the client sent, and no header that a client cannot forge, so `ops` and its audit record the address of the ALB. The operator is named by the SAML login and, for the CLI, by IAM. `app` reads the client address from `CloudFront-Viewer-Address` alone, which CloudFront overwrites.

The service tenant `ops-sso`, where operators log in, is a tenant domain like any other and takes the path through CloudFront.

## Inside the VPC

| Client | Server | Protocol | Trusted |
|---|---|---|---|
| `app`, `worker`, `ops`, the CLI | PgBouncer at `pgbouncer.idp.internal:5432` | Postgres with TLS, `verify-full` | The internal CA in `DB_TLS_CA` |
| PgBouncer | RDS | Postgres with TLS | The RDS roots of the region |
| `migrator`, the recovery run, the provisioner in `worker`, the bootstrap tasks | RDS | Postgres with TLS, `verify-full` | The RDS root of `ca_cert_identifier` |
| `app` | ElastiCache Valkey on 6379 | `rediss://` with the AUTH token | The roots of Amazon Trust Services in `VALKEY_TLS_CA` |
| `app`, `worker`, `ops`, the CLI | The collector at `otel-collector.idp.internal:4318` | OTLP over HTTPS | The internal CA in `OTLP_TLS_CA`, the bundle `otlp` of every exporter |
| ECS in the task of `worker` | `worker` on `localhost:8081` | HTTP | Its own health check inside the task. The security group of `worker` admits nothing |

The deployed profiles refuse a server of `app` or `ops` without TLS and an OTLP endpoint without TLS or without `OTLP_TLS_CA` (`TlsGuard`). See [the TLS of Postgres and Valkey](../../infra/terraform/README.md#tls-to-postgres) for the database and the cache.

## To AWS

Every call is HTTPS to the regional name of the service, which the private DNS of the interface endpoint answers, signed with the role of the caller. The AWS SDK and the collector trust the public roots of their platform.

| Service | Callers | Calls |
|---|---|---|
| KMS | `app`, `worker`, `ops`, the CLI, the recovery run | The rights of `infra/terraform/README.md`, Rights in AWS |
| STS | The CLI | `GetCallerIdentity` |
| S3, through the gateway endpoint | `worker`, the recovery run, ECS | The erasure journal, image layers |
| Secrets Manager, ECR | ECS with the execution role of each task | Its secrets and its image |
| CloudWatch Logs | ECS, the collector | The console of each task, the OTLP lines and the EMF metrics, see [logging.md](logging.md) |
| X-Ray | The collector | Spans over OTLP, which X-Ray stores in `aws/spans` |
| SES on 587, through the endpoint `email-smtp` | `worker` | SMTP with STARTTLS required and the name of the server checked |

## External services

The application relies on AWS alone at runtime: CloudFront, WAF, ACM, S3, the load balancers, ECS on Fargate with Cloud Map, RDS, ElastiCache, KMS, Secrets Manager, ECR, CloudWatch, X-Ray and SES. GitHub Actions reaches the accounts only through OIDC for releases and Terraform, and Terraform downloads the RDS and Amazon Trust Services roots at plan time. No service calls anything outside AWS.

## Addresses a user gives

No user chooses where a service connects. The addresses come from configuration that Terraform sets: the database, Valkey, the collector, the KMS region and SES. A tenant chooses none of them:

- **SAML.** Metadata of an SP is uploaded as a file, never fetched from a URL. The IdP never connects to an ACS URL: the browser posts the response there.
- **Mail.** The address of a user is a recipient, and every message goes to SES. Links in a mail use the stored host of the tenant, never the request.
- **Keys.** `worker` creates the KEK of a tenant and stores its URI. The scheme of the URI selects an adapter, whose address is configuration.
- **Host.** The `Host` of a request selects a tenant among the stored domains and opens no connection.

Without a route out and with egress to named groups alone, a fault that took an address from a request could still reach nothing outside these lists.

## Local

`docker-compose.yml` runs the same services with fixed test credentials. Traefik terminates TLS with the certificate of mkcert, and `app` and `ops` serve plain HTTP behind it with `SERVER_TLS_ENABLED=false`, which the deployed profiles refuse. Postgres, PgBouncer, Valkey, OpenBao, Mailpit and LGTM listen without TLS on the compose network.
