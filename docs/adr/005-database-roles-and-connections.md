# ADR-005: Database roles and connections

## Status

Accepted

## Decision

- **Owner:** `idp_owner` owns every schema and holds `CREATEROLE`. `migrator` runs as it, and the provisioner in `worker` connects with the role `idp_owner` to create tenant schemas.
- **Tenant role:** every tenant schema has a `NOLOGIN` role with the same name, `tenant_<uuid>`. It gets DML on its own schema, `USAGE` on `platform` and `INSERT` on `platform.outbox`, and nothing else.
- **Runtime roles:** `app_runtime`, `worker_runtime` and `ops_runtime` log in with `NOINHERIT`. `app_runtime` and `worker_runtime` are granted every tenant role `WITH INHERIT FALSE, SET TRUE`, so they reach tenant data only through `SET LOCAL ROLE`. `ops_runtime` gets no tenant role and only the `platform` tables it needs.
- **Platform access:** code reads and writes `platform` through `PlatformTransaction`. Grants are per table and per runtime, so `app` only reads tenants and their domains.
- **Connections:** `app`, `worker` and `ops` connect through PgBouncer in transaction mode with `prepareThreshold=0`. Every setting is `SET LOCAL` and ends with its transaction. `migrator` and the provisioner connect to Postgres directly, because migrations and role changes need session state that transaction pooling does not keep.
- **Migrations:** all platform and tenant migrations and the generated jOOQ code live in `backend/db`.

## Rationale

The database, not the application, decides which tenant a transaction can touch. A tenant role holds no rights outside its schema, and a runtime that does not inherit it has no tenant rights until it switches for one transaction. A bug that forgets the switch fails on missing rights instead of reading the wrong tenant. Postgres checks `SET ROLE` against the session user, so code that can run arbitrary SQL as `app_runtime` can still switch to any tenant. Parameterised jOOQ queries guard that path.

`SET LOCAL` keeps a pooled connection clean for the next tenant, which is what lets hundreds of tenants share one PgBouncer pool. One migrations module means `migrator` and `worker` always see every file, and a per-module history would add ordering problems without any isolation gain, since every tenant schema runs the same set.
