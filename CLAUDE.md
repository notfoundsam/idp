# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

A multi-tenant SAML Identity Provider for B2B customers. `docs/architecture.md` maps the modules and the request path, and `docs/adr/` holds the decisions. `docs/security/` holds the ASVS map, the crypto inventory and the runbooks, `docs/release.md` the releases, `docs/portability.md` the way off AWS. Implementation plans live in `docs/plans/` and are never committed. For a plan task, read the plan's `README.md` and `tasks/task-NN.md`, and open its other files only when needed.

- `backend/` — Kotlin, Spring Boot, Gradle multi-project. The root package is `idp`.
- `frontend/` — Vue 3, pnpm workspace.
- `contracts/openapi/` — API contracts, one per audience.
- `infra/` — Terraform for AWS in `terraform/` (see its `README.md`), release scripts in `deploy/`, static file headers and routes in `web/`, images of PgBouncer, the database bootstrap and the collector.

## Architecture rules

- `backend/modules/<context>` holds bounded contexts. Their public API is `application/*Service`, read models and event types. They contain no commands, queries or controllers. The only exception is the SAML protocol adapter in `federation`.
- `backend/audience/<audience>` owns commands, queries, controllers, `*SecurityConfig` and responses.
- Direct calls between contexts follow the graph `federation → authentication → identity` and `federation → identity`. The audience or `worker` composes everything else.
- `audience/operator` is built only into `ops`, the other audiences only into `app`. `worker` and `migrator` never depend on `audience`.
- `backend/db` owns all migrations and jOOQ code. A table is accessed only from its owner's `adapters/persistence`.
- The AWS SDK is used only in the `adapter.aws` packages of `shared-kernel`, so the services stay portable (`docs/portability.md`).
- These rules live in `backend/architecture-tests`. A new rule needs a fixture with a violation, and a new table needs an entry in `tableOwners`.

## Conventions

- Tests: write them in whatever order suits the task. Every change ships with tests, including failure cases. Approach, tools and fixtures are in `docs/testing.md`.
- Accepted migrations are never edited. Add a new `V<n>__*.sql`.
- Every sensitive action writes through `AuditLog` and has a test for it.
- A change that adds, removes or weakens a security control updates its row in `docs/security/asvs-l2.md`. Find the row by requirement number with `grep -n`, the file is too long to read whole.
- ADRs state the decision, then its rationale once.
- Comments: default to none. Write one only for a non-obvious why.
- Name repository fields after what they store: `users`, not `userRepository`.

## Tests

- Database tests start from `TestDatabase.migrated()`, a clone of a template migrated once per test JVM. `TestDatabase.create()` gives an empty database and is only for tests of the migrations themselves.
- `database.tenantSchema()` hands out a migrated tenant schema from a pool, and the test adds its `platform.tenants` row. Every clone hands out the same ids, so nothing outside Postgres may be keyed by a pooled tenant: give OpenBao keys random names, and give a tenant a random id when Valkey counts limits for it.
- Under load the Docker VM can stall for seconds, and its clock then lags the host. A test that sets a time with SQL `now()` and checks it in the JVM depends on two clocks, so it sets such times from the test's clock.
- `./gradlew check` fails when line coverage of `shared-kernel`, `identity`, `authentication`, `federation` or an audience drops below 80%. Cover the line with a test, never exclude it.
- A new tool or kind of test updates `docs/testing.md`.

## Infrastructure

- Never run `terraform plan` or `apply`, the `aws` CLI or the workflows `Deploy` and `Rollback`. People apply environments as `infra/terraform/README.md` says. Check Terraform only with `make lint-infra` and `make test-infra`, which read no state and call no AWS API.
- A module with tests goes into `TERRAFORM_TESTED_MODULES` of the `Makefile`. Its tests plan against `mock_provider "aws"`, which has no ephemeral resources, so generated secrets live in untested modules such as `modules/keys`.
- `infra/deploy` is a pnpm project of its own, tested with `node:test` against the fakes of S3, ECS and the IdP. No test calls AWS.
- A new route of the apps goes into `infra/web/spa-routes.json`, `spa-rewrite.js` and `nginx.conf.template` together.

## Commands

```bash
make install           # once: /etc/hosts, certificates, dependencies, jars
make up                # start the local environment, see dev-tools/README.md
make smoke             # check every local service
make test              # backend, frontend and the release scripts (test-deploy)
make test-e2e          # Playwright against a running `make up`
make test-infra        # terraform test against a mocked AWS provider
make lint              # ktlint, frontend lint and types, lint-infra (terraform, TFLint, actionlint)
make check-registries  # dependencies come only from the allowed registries
make build             # assemble everything
```

Gradle runs on the host and needs a JVM to start, because Testcontainers would need the Docker socket from inside a container. Compiling also needs Docker: jOOQ code is generated from the migrations on a temporary Postgres. The Corretto 25 toolchain is provisioned automatically, the same JVM as the production image.

The frontend runs only in Docker, so the host needs no Node. Use `docker compose run --rm frontend corepack pnpm ...`. `node_modules` lives in a named volume, and pnpm is pinned in `frontend/package.json`.

Kotlin stays at the newest version CodeQL supports, currently 2.4.20. `renovate.json` holds the limit.

CodeQL analyzes Kotlin, JavaScript and TypeScript on every PR with `security-extended`, and the job fails on a finding of CVSS medium or above (`infra/deploy/code-scanning.mjs`). Its Kotlin extractor runs inside the Kotlin daemon, so `kotlin.daemon.jvmargs` in `backend/gradle.properties` gives that daemon 3 GiB: with the 512 MiB it inherits from Gradle, the build runs out of heap.

## Task board

Every plan task has an issue `Task N: ...` on the [project board](https://github.com/users/notfoundsam/projects/1). The repository is public and plans are not, so issues hold only the goal.

1. Starting a task: move its issue to `Implementing`.
2. Code and tests are done: move it to `Testing`.
3. Open a PR with `Closes #N`, then move the issue to `Review`. Linking a PR resets the status to `Implementing`, so this step goes last.
4. Merging the PR closes the issue and moves it to `Done`.

## Git workflow

- Stage with `git add -u` plus explicit new paths. Never use `git add -A` or `git add .`.
- Never stage `docs/plans/`.
- Commit messages: a one-line conventional subject, with a body only for a non-obvious why.
