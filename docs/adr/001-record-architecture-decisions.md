# ADR-001: Baseline architecture

## Status

Accepted

## Decision

A multi-tenant SAML Identity Provider for B2B customers, built as one Kotlin and Spring Boot monolith deployed with different privileges. Later ADRs refine single decisions.

- **Core:** the authentication engine and the SSO session. SAML is a protocol adapter, so login through an SP and direct login share one `AuthFlow`.
- **Deployables:** `app`, `worker`, `ops` and the one-shot `migrator` run with separate database roles and KMS rights.
- **Isolation:** one Postgres schema per tenant, named from the tenant UUID. Every tenant transaction runs `SET LOCAL ROLE` and `SET LOCAL search_path`.
- **Operator boundary:** `ops` sees only the `platform` schema and cannot call `Decrypt`. Provisioning runs in `worker`.
- **Encryption:** tenant KEK in KMS, then a tenant key, then per-user DEKs. Wrapped keys live in the tenant schema.
- **Layers:** bounded contexts in `modules/` expose services. Audiences in `audience/` own CQRS messages, controllers, permissions and responses.
- **Context graph:** `federation → authentication → identity` and `federation → identity`. `tenancy`, `identity` and `audit` depend on no other context.
- **Events:** one `platform.outbox` table holding IDs only. Audit is a synchronous port, not an event consumer.
- **Portability:** the application speaks open protocols, and the AWS SDK lives in one adapter, so a move on-premises replaces infrastructure rather than code.

## Rationale

Tenant data is the product's main asset, so isolation and the operator boundary are enforced by the database and KMS rather than by application checks. A code bug cannot cross them, although remote code execution on `app` still can. A monolith keeps those boundaries without the operational cost of services, while separate deployables still give each process only the rights it needs.
