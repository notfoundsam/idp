# ADR-002: Audiences as backends for frontends

## Status

Accepted

## Decision

Audiences own the command and query bus. Bounded contexts expose services and know nothing about the bus.

- **Context API:** a context in `modules/` offers `application/*Service`, read models and event types. Service methods are typed, synchronous and called directly. A context holds no commands, queries, handlers or controllers, and the SAML adapter in `federation` is the only exception.
- **Audience:** `audience/{login,account,admin,operator}` owns commands, queries, handlers, controllers, `*SecurityConfig`, `*ThrottleConfig` and responses. A response belongs to its audience, so two audiences can shape the same data differently.
- **Pipeline:** every message passes Throttle, Security, Logger, Transaction and its handler in that order. Exactly one handler serves a message, and the bus refuses to start otherwise.
- **Permissions:** `*SecurityConfig` maps a message to a permission and a role to its permissions. A message without an entry is refused. `null` makes a message public, and every public message is listed in `ArchitectureRules.publicMessages`.
- **Transactions:** a message declares `TENANT`, `PLATFORM` or `NONE`. `NONE` is for slow work such as Argon2id, which must not hold a PgBouncer connection.
- **Writes across contexts:** one audience handler calls several services inside the one transaction the decorator opened.
- **Composite queries:** a screen that needs data from several contexts gets one query, and its handler calls the read methods of each service. A controller does not dispatch two messages for one response.
- **System actions:** `worker` and `migrator` call services directly, without the bus. `IdempotentHandler`, `TenantJobRunner` or the process itself opens the transaction, and the database role and IAM set the boundary.
- **Throttling:** only `app` throttles, because `ops` and its CLI sit behind VPN and IAM. Counters live in Valkey behind the `RateLimiter` port. When Valkey fails the limiter fails open behind a circuit breaker and counts the failure, and the protections in Postgres keep working.

### Caching

No cache is built in the first version. If one appears, these rules apply.

- **Not cached:** open PII, keys, sessions, SP assignments, user and tenant statuses.
- **May be cached:** data without PII that is expensive to fetch and rarely changes, such as SP metadata and tenant branding.
- **Key:** `<format>:<tenant>:<entity>:<id>:<version>`.
- **Connection point:** a decorator on the query bus, and a query declares whether it is cached. Contexts never call Valkey directly.
- **HTTP first:** when `ETag` with `304` suits the client, Valkey is not needed.

## Rationale

A context called from one place needs no bus of its own. Decorators exist to apply one policy at the edge of the system, and that edge is the audience. Inside a context the same chain would run twice for one request, and a service call would need a message, a handler and a security entry for nothing.

Keeping permissions in the audience lets one context serve a user, an admin and an operator with different rules. Denying by default means a forgotten entry fails closed. One composite query keeps one throttle decision, one log line and one transaction per response, so a page cannot half succeed.

Throttle runs first so a flood reaches neither the database nor the audit log. Security runs before Transaction so a denial needs no connection held by the handler and survives whatever the handler would have done. Logger stays outside Transaction so a failed commit is logged.

Open PII in Valkey would sit outside envelope encryption, and cached statuses or assignments would delay revocation. The aggregate version in the key gives freshness without invalidation, and the format version keeps old and new code from reading each other's entries during a rollout.
