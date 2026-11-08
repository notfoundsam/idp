# ADR-009: AWS infrastructure

## Status

Accepted

## Decision

- **Accounts and state:** staging and production live in AWS accounts of their own. `infra/terraform/bootstrap` gives each a state bucket encrypted by its own KMS key with S3 lock files, and roles for GitHub Actions through OIDC: `terraform` for the GitHub environment `<env>-infra`, `deploy` for `<env>`.
- **Network:** the private subnets have no route to the internet. Tasks reach AWS through VPC endpoints, and each workload has a security group to which the module that connects two workloads adds the rules of both sides.
- **Credentials:** Terraform generates passwords into Secrets Manager as write-only values, and RDS manages its master password itself. ECS hands secrets to the containers as variables, and the images of `infra/` turn them into the files their programs read.
- **TLS:** every client of an internal service verifies the certificate and its name, and trusts one CA alone. Internal hosts get certificates from the CA of `modules/pki`, whose key lives only in the encrypted state. RDS is trusted through its roots for the region. The JDBC URLs use `SingleCertValidatingFactory` with the certificate in a variable, so the images carry no trust store.
- **Database bootstrap:** RDS has no superuser, so a one-off task creates `idp_owner` and `provisioner` as the master user, and another sets the passwords of the runtime roles as `idp_owner` after `migrator` created them.

## Rationale

Without a route out, a compromised task can reach only the AWS services and the hosts its security group names. Write-only secrets keep passwords out of the plan, the state and CI logs, which a generated value in a regular attribute would not. One trust anchor per connection means a certificate from any public CA cannot impersonate the database. A private CA in the state needs no protection beyond what the state already has, where AWS Private CA would add a monthly fee per environment for a few certificates. The same images and scripts run against plain Postgres, so an on-premises installation keeps them.
