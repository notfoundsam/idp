# IdP

A multi-tenant SAML Identity Provider for B2B customers.

- **Users** sign in through a Service Provider of their company or directly at the IdP, with a password, TOTP, recovery codes or a passkey, and manage their factors and sessions in the console.
- **Tenant admins** manage the users of their tenant, its login policy, its Service Providers and who may use them, and read its audit journal.
- **The operator** of the platform enters and approves tenants through a CLI and an API, and has no access to the data of a customer.

Each tenant lives in a Postgres schema of its own, and its personal data is encrypted under a key of its own in a KMS. [ADR-001](docs/adr/001-record-architecture-decisions.md) gives the baseline.

## Repository

```
backend/      Kotlin and Spring Boot: one monolith built into app, worker, ops and migrator
frontend/     Vue 3 pnpm workspace: the login and console apps, the API client, end-to-end tests
contracts/    OpenAPI contracts, one per audience
infra/        Terraform for AWS, the release scripts, the images of PgBouncer, the database bootstrap and the collector
dev-tools/    The local environment of docker-compose.yml
docs/         Architecture, decisions, security, testing, releases
```

## Local environment

The host needs Docker, a JVM to run Gradle and [mkcert](https://github.com/FiloSottile/mkcert). The frontend runs in Docker, so Node is not needed.

```bash
make install   # /etc/hosts entries (asks for sudo), certificates, dependencies, jars
make up        # builds the jars, starts everything, opens https://dashboard.idp.test
make smoke     # checks every service
```

`make up` provisions the tenants `acme` and `globex` and the service tenant `ops-sso`, and adds the users `alice@acme.test` and `bob@acme.test` to `acme` with SimpleSAMLphp as a test SP. Their passwords, TOTP secret and the other services are in [dev-tools/README.md](dev-tools/README.md).

## Commands

```bash
make test        # backend, frontend and the release scripts
make test-e2e    # Playwright against a running `make up`
make test-infra  # terraform test of the modules against a mocked AWS provider
make lint        # ktlint, ESLint and the type check, Terraform, TFLint and actionlint
make build       # every jar and app
make stop        # stops the containers and keeps the data
make clean       # removes the containers and the data
```

`docs/testing.md` lists the rest.

## Documentation

| Document | Holds |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Modules, deployables, the path of a request, where new code goes |
| [docs/adr/](docs/adr/) | Architecture decisions |
| [docs/security/](docs/security/) | The OWASP ASVS Level 2 map, data protection, the crypto inventory, limits, logging, and the runbooks |
| [docs/testing.md](docs/testing.md) | Test layers, tools, fixtures and the acceptance criteria |
| [docs/release.md](docs/release.md) | Releasing and rolling back an environment |
| [docs/portability.md](docs/portability.md) | Moving the keys to another KMS and the platform off AWS |
| [infra/terraform/README.md](infra/terraform/README.md) | The AWS environments, their rights and credentials |
| [dev-tools/README.md](dev-tools/README.md) | The local environment |

## Status

The first version is complete in the repository and runs locally. It speaks SAML 2.0 with SP-initiated login, without Single Logout, and the operator works through the CLI and the API, without a UI of its own.

Before the first customer:

- **Region and domain:** the AWS region and the product domain are placeholders in the `*.example` files of `infra/terraform`, `eu-central-1` and `idp.example.com`. Passkeys are bound to the domain, so it must not change after the first users. `PasswordPolicy` refuses the product name in a password and still names it `idp`.
- **Staging checks:** a few controls are proven only against LocalStack or a mocked AWS provider. Their rows in [asvs-l2.md](docs/security/asvs-l2.md) have the status `Staging` and name the check that remains.

## License

MIT. See [LICENSE](LICENSE).
