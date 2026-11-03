# Architecture

A map of the code: where things live and how a request moves through them. Decisions and their reasons are in `docs/adr/`.

## Modules

| Path | Holds |
|---|---|
| `backend/shared-kernel` | Tenant context and transactions, bus, outbox, scheduling, crypto and KMS adapters, erasure journal, `AuditLog` port, test fixtures |
| `backend/db` | Migrations in `db/platform` and `db/tenant`, generated jOOQ code |
| `backend/modules/<context>` | `tenancy`, `identity`, `authentication`, `federation`, `audit`. Each has `domain`, `application/*Service` and `adapters/persistence`. `federation` also has the SAML endpoints in `adapters/saml` |
| `backend/audience/<audience>` | `login`, `account`, `admin`, `operator`: commands, queries, controllers, `*SecurityConfig`, `*ThrottleConfig`, responses |
| `backend/{app,ops,worker,migrator}` | Deployables, see below |
| `backend/architecture-tests` | ArchUnit rules, the build graph and `tableOwners` |
| `contracts/openapi` | `auth.yaml` for `app`, `ops.yaml` for `ops` |
| `frontend` | Apps `login` and `console`, packages `api-client` and `ui`, end-to-end tests in `e2e` |
| `dev-tools`, `docker-compose.yml` | Local environment, see `dev-tools/README.md` |

## Deployables

| Deployable | Runs | Database role |
|---|---|---|
| `app` | Audiences `login`, `account`, `admin`, the web filters, the bus with Throttle, SAML endpoints | `app_runtime` |
| `ops` | Audience `operator`, the bus without Throttle, the operator CLI | `ops_runtime`, `platform` only |
| `worker` | `OutboxDispatcher`, scheduled jobs, tenant provisioning and offboarding, mail, key rotation | `worker_runtime`, provisioning as `idp_owner` |
| `migrator` | Migrations, then `ReapplyErasuresJob` after a restore | `idp_owner` |

## A request in `app`

1. **Filters**, in order: `ClientVersionFilter` (`426` for an outdated frontend), `RequestSizeFilter`, `TenantFilter`, `SessionFilter`, then `CsrfFilter` and `SamlThrottleFilter`.
2. **Tenant:** `TenantFilter` looks up the full `Host` in `platform.tenant_domains` through a cache. Only an `ACTIVE` tenant passes, and its id is bound to `TenantContext` for the rest of the request.
3. **Session:** `SessionFilter` reads the session cookie and calls `AuthenticationService.resolveSession` in the tenant's schema. A found session puts a `Principal` into the security context. Without a cookie the database is not touched.
4. **Controller and bus:** a controller in an audience dispatches one message. The bus runs Throttle, Security, Logger, Transaction and the handler, in that order.
5. **Transaction:** `TenantTransaction` takes the schema name from the tenant UUID and runs `SET LOCAL ROLE` and `SET LOCAL search_path`. jOOQ queries carry no schema, so they hit that tenant's tables.
6. **Services:** the handler calls `*Service` methods of one or more contexts inside that one transaction.

SAML endpoints (`SsoEndpoint`, `ResumeEndpoint`, `MetadataEndpoint`) skip the bus and call `FederationService` and `AuthenticationService` directly. `SamlThrottleFilter` limits them.

## Background work in `worker`

1. **Publish:** a service writes an event to `platform.outbox` through `OutboxPublisher`, in the same transaction as the change. Events carry IDs only.
2. **Dispatch:** `OutboxDispatcher` picks up the event. `IdempotentHandler` runs the handler in a tenant transaction and records the event in that tenant's `processed_events`, so a repeat does nothing.
3. **Scheduled jobs:** every worker ticks `JobScheduler`, and a row in `scheduled_locks` lets only one of them run each due job. `TenantJobRunner` runs a job for each tenant in turn.

Handlers and jobs call services directly, without the bus.

## Actors

A `Principal` pairs an `Actor` with a role. Service methods that write audit records take the `Actor` explicitly: `User`, `Operator`, `System` for `worker` and `migrator`, or `Anonymous` for a failed login. In `ops` the operator CLI builds the principal from its caller's identity, and the `local` profile and tests use a fixed operator.

## Where things go

- **New table:** a new `V<n>__*.sql` in `backend/db/src/main/resources/db/platform` or `db/tenant`, an entry in `tableOwners`, access only from the owner's `adapters/persistence`.
- **New endpoint:** the contract in `contracts/openapi` first, then the message, its `*SecurityConfig` entry and the controller in the audience.
- **Sensitive action:** a record through `AuditLog`.
- **Security control:** its row in `docs/security/asvs-l2.md`.
- **Work without a user:** a handler or job in `worker` that calls services.

## Other documents

- `docs/adr/`: 001 baseline, 002 audiences and bus, 003 password hashing, 004 SAML library and keys, 005 database roles and connections, 006 state and consistency, 007 release compatibility
- `docs/security/`: ASVS map, authentication, sessions, limits, data protection, crypto inventory and runbooks
- `docs/testing.md`: test layers, tools and fixtures
