# ADR-009: AWS infrastructure

## Status

Accepted

## Decision

- **Accounts and state:** staging and production live in AWS accounts of their own. `infra/terraform/bootstrap` gives each a state bucket encrypted by its own KMS key with S3 lock files, and roles for GitHub Actions through OIDC: `terraform` for the GitHub environment `<env>-infra`, `deploy` for `<env>`.
- **Network:** the private subnets have no route to the internet. Tasks reach AWS through VPC endpoints, and each workload has a security group to which the module that connects two workloads adds the rules of both sides.
- **Credentials:** Terraform generates every credential of the services into Secrets Manager: passwords, the Valkey token and the HMAC key of Throttle as write-only values, the SMTP credentials of SES from the access key of an IAM user, which lives in the encrypted state. RDS manages its master password itself. ECS hands secrets to the containers as variables, and the images of `infra/` turn them into the files their programs read.
- **TLS:** every client of an internal service verifies the certificate and its name, and trusts the roots of one issuer alone. Internal hosts get certificates from the CA of `modules/pki`, whose key lives only in the encrypted state. RDS is trusted through its roots for the region, ElastiCache through the roots of Amazon Trust Services. The JDBC URLs use `SingleCertValidatingFactory` and `app` its own trust manager for Valkey, each with the certificates in a variable, so the images carry no trust store.
- **Edge:** the public ALB takes requests only from CloudFront: through the managed prefix list of its origin-facing servers and a generated header that the listener requires. A WAF on the distribution limits each viewer address, and `app` reads that address only from `CloudFront-Viewer-Address`.
- **Database bootstrap:** RDS has no superuser, so a one-off task creates `idp_owner` and `provisioner` as the master user, and another sets the passwords of the runtime roles as `idp_owner` after `migrator` created them.

## Rationale

Without a route out, a compromised task can reach only the AWS services and the hosts its security group names. Write-only secrets keep passwords out of the plan, the state and CI logs, which a generated value in a regular attribute would not. SES offers SMTP only with credentials derived from an IAM access key, so that key is the one generated credential in the state. One issuer per connection means a certificate from any other CA cannot impersonate the database or Valkey. Behind CloudFront the ALB sees only edge servers, so only the edge can count by viewer, and only a header that CloudFront overwrites carries the viewer to `app`. The prefix list admits the distributions of every account, so the header proves that a request passed this one and its WAF. A private CA in the state needs no protection beyond what the state already has, where AWS Private CA would add a monthly fee per environment for a few certificates. The same images and scripts run against plain Postgres, so an on-premises installation keeps them.
