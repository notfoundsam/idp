# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

A multi-tenant SAML Identity Provider for B2B customers. See `docs/adr/` for decisions. Implementation plans live in `docs/plans/` and are never committed.

- `backend/` — Kotlin, Spring Boot, Gradle multi-project. The root package is `idp`.
- `frontend/` — Vue 3, pnpm workspace.
- `contracts/openapi/` — API contracts, one per audience.

## Architecture rules

- `backend/modules/<context>` holds bounded contexts. Their public API is `application/*Service`, read models and event types. They contain no commands, queries or controllers. The only exception is the SAML protocol adapter in `federation`.
- `backend/audience/<audience>` owns commands, queries, controllers, `*SecurityConfig` and responses.
- Direct calls between contexts follow the graph `federation → authentication → identity` and `federation → identity`. The audience or `worker` composes everything else.
- `audience/operator` is built only into `ops`, the other audiences only into `app`. `worker` and `migrator` never depend on `audience`.
- `backend/db` owns all migrations and jOOQ code. A table is accessed only from its owner's `adapters/persistence`.
- These rules live in `backend/architecture-tests`. A new rule needs a fixture with a violation, and a new table needs an entry in `tableOwners`.

## Conventions

- TDD: write the test first, including failure cases.
- Accepted migrations are never edited. Add a new `V<n>__*.sql`.
- Every sensitive action writes through `AuditLog` and has a test for it.
- ADRs state the decision, then its rationale once.
- Comments: default to none. Write one only for a non-obvious why.
- Name repository fields after what they store: `users`, not `userRepository`.

## Commands

```bash
make up        # start the local environment, see dev-tools/README.md
make smoke     # check every local service
make test      # backend and frontend tests
make lint      # ktlint and frontend lint
make build     # assemble everything
```

Gradle runs on the host and needs a JVM to start, because Testcontainers would need the Docker socket from inside a container. Compiling also needs Docker: jOOQ code is generated from the migrations on a temporary Postgres. The Corretto 25 toolchain is provisioned automatically, the same JVM as the production image.

The frontend runs only in Docker, so the host needs no Node. Use `docker compose run --rm frontend corepack pnpm ...`. `node_modules` lives in a named volume, and pnpm is pinned in `frontend/package.json`.

Kotlin stays at the newest version CodeQL supports, currently 2.4.20. `renovate.json` holds the limit.

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
