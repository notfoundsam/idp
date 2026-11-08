# AWS infrastructure

Terraform for staging and production. Each environment lives in an AWS account of its own.

| Path | Holds |
|---|---|
| `bootstrap/` | The state bucket with its KMS key, the GitHub OIDC provider and the roles GitHub Actions assume. Applied once per account by hand |
| `environments/<env>/` | The root of an environment: network, ECR, static bucket, ECS cluster, internal CA, database and PgBouncer |
| `modules/network` | VPC without a route to the internet, AWS services through VPC endpoints, a security group per workload, the public ALB |
| `modules/registry` | ECR repositories with immutable tags |
| `modules/static` | The bucket of the frontend files, read only by CloudFront |
| `modules/cluster` | The ECS cluster and the private DNS namespace `idp.internal` |
| `modules/pki` | The internal CA and the server certificates it issues, their keys in Secrets Manager |
| `modules/data` | RDS, the role passwords, the bootstrap tasks and PgBouncer, see `infra/pgbouncer` and `infra/db-bootstrap` |

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

## Database passwords

Terraform generates every role password into Secrets Manager as a write-only value, so no password reaches the plan, the state or the repository. `ProductionDatabaseTest` runs the same images and connection settings against a Postgres that behaves like RDS.

To replace a password, raise its version in `db_password_versions` and apply. Then run `owner` for `idp_owner` and `provisioner` or `role-passwords` for a runtime role, and redeploy PgBouncer and the services that use the role. Connections that PgBouncer opened before keep working until they close.

## TLS to Postgres

Every client verifies the server certificate and its name, `sslmode=verify-full`, and trusts one CA alone, never the public ones:

| Connection | Host | Trusted CA |
|---|---|---|
| `app`, `worker` and `ops` to PgBouncer | `pgbouncer.idp.internal` | The internal CA of `modules/pki` |
| PgBouncer to RDS | The RDS endpoint | The RDS roots of the region |
| `migrator`, the provisioner in `worker` and the bootstrap tasks to RDS | The RDS endpoint | The RDS root of `ca_cert_identifier` |

The JDBC URLs in the output `database` read the certificate from a variable: `DB_TLS_CA` for `DB_URL`, `PROVISIONER_DB_TLS_CA` for `PROVISIONER_DB_URL`. PgBouncer accepts only TLS clients, and RDS only TLS connections through `rds.force_ssl`.

The internal CA is a key in the Terraform state, which its own KMS key encrypts and only the `terraform` role reads. An apply in the last 60 days of a server certificate issues a new one, and the CA itself lives ten years.
