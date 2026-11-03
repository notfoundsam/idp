# ADR-006: State and consistency

## Status

Accepted

## Decision

- **Versions:** a mutable aggregate has a `version` column. An update matches the version it read and raises `VersionConflictException` otherwise. Security counters and `last_seen` stay outside the version and change with an atomic `UPDATE`.
- **Security state:** whatever must not be lost lives in Postgres: TOTP replay, attempts, lockouts, recovery codes and SAML request IDs. Limits that may be lost sit on the WAF and in Valkey behind Throttle.
- **Audit:** `AuditLog` is a synchronous port, not an outbox consumer. A change is recorded in the transaction that makes it, and a denial in a separate transaction. Operator actions go to `platform.operator_audit`.
- **Erasure journal:** erasures are written outside the database behind the `ErasureJournal` port, to S3 with Object Lock in AWS and to a file locally. After a restore, `migrator` applies the journal again.
- **User aggregates:** `User` owns login and status and `UserProfile` owns the profile. Each kind of credential is an aggregate of its own, and they refer to each other by ID only.

## Rationale

Optimistic versions catch two admins editing one aggregate without holding locks across a request. A failed login must still count when another request changed the same user, so counters cannot share the version.

State lost in Valkey only resets a rate limit. Lost TOTP replay data or SAML request IDs would let a code or an assertion be used twice, so those live in the transactional store.

An audit record committed with its change cannot describe a change that was rolled back, and a denial in its own transaction survives the rollback of the request it refused.

A journal inside the database would be rolled back with the database, and a restore would bring back users who asked to be erased. Keeping it outside lets the restore replay every erasure made after the backup.

Login, profile and credentials change for different reasons and at different rates. Separate aggregates keep a password change from conflicting with a profile edit.
