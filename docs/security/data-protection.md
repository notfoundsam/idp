# Data protection

Every piece of data the IdP keeps falls into one class below, and the class sets how it is stored, kept, logged and reached. A new field takes the class of what it holds. When a field fits none, a new class goes here first.

Everything travels over TLS: browsers to CloudFront and the ALB, services to Postgres, Valkey, KMS, S3 and SMTP. The classes below differ only at rest.

## Classes

| Class | What it holds | Examples |
|---|---|---|
| Key material | Keys that open other data | KEKs in KMS, the tenant key, user keys (`user_keys`), SAML signing keys, the platform KEK |
| Credentials | What proves a person or a browser | Password hashes, TOTP secrets, recovery codes, passkey public keys, session tokens, invitation, reset and email change tokens |
| Personal data | What names or reaches a person of a customer | Email, name, phone, SP attributes, IP and User-Agent of a session, the login typed into a flow, IP and details of an audit record |
| Customer configuration | What a tenant admin sets | Policies, SP registrations and their metadata, assignments to SPs |
| Platform records | What the operator works with | Tenant names and subdomains, statuses, application contacts, the operator journal |
| Identifiers | Random IDs that name nothing by themselves | Tenant, user and SP IDs, outbox events, the erasure journal |

## Requirements

### Key material

- Never leaves KMS as a KEK. The tenant key is stored wrapped by the tenant KEK, user keys and SAML keys wrapped by keys below it.
- Unwrapped keys live only in memory, at most for the cache TTL of `KeyHierarchy`.
- Destroying a key is how data is erased: a user key goes with the user, the tenant KEKs with the tenant. After a move to another KMS a tenant keeps its previous KEK for `KMS_PREVIOUS_KEK_RETENTION`, 35 days by default and 45 in AWS, so backups taken before the move still open, see [crypto-inventory.md](crypto-inventory.md).
- Only `app`, `worker` and the recovery run of `migrator` after a restore may call `Decrypt` on tenant KEKs, and only `worker` and the recovery run may delete one. `ops` and the CLI may only `Encrypt` with the platform KEK, which only `worker` decrypts.
- Never logged, never in an event, never in an API response.

### Credentials

- Stored only in a form that cannot log anyone in: Argon2id for passwords, SHA-256 for tokens and recovery codes, the TOTP secret encrypted with the user key. Passkeys keep only the public key.
- Tokens are single use and short lived: invitations 72 hours, reset and email change 1 hour, flows 15 minutes, sessions at most the absolute timeout of the policy. Cleanup jobs delete what has expired.
- A token travels in a URL fragment or a request body, never in a query string.
- Never logged, never in a trace and never in an audit record, not even as a hash.

### Personal data

- Encrypted field by field with AES-256-GCM under the key of its owner. The associated data names the field and the row, so a ciphertext copied elsewhere does not decrypt.
- Email lookup goes through a blind index, an HMAC under a key derived from the tenant key.
- An audit record keeps the IP and details under the key of the actor, so erasing a user also erases what they left in the journal, but not what others did to them.
- Erasing a user deletes the row and the user key at once. Copies in backups become unreadable with the key and expire with the backups after 30 days. The erasure journal brings the erasure back after a restore, see [restore-runbook.md](restore-runbook.md).
- Never in logs, events, exception messages, metrics or traces. Logs, events and traces carry IDs only.
- Reached only inside the transaction of its tenant, under the role of that tenant. `ops` cannot reach it.

### Customer configuration

- Stored in the tenant schema, in plain columns where it holds no personal data.
- Changes go through `AuditLog` with the acting admin.
- Lives as long as the tenant.

### Platform records

- An application contact is encrypted with the platform KEK. `ops` sees only its mask, and `worker` erases it once the tenant is active. A contact for a new admin invitation is deleted after use.
- The operator journal is append-only for `ops` and holds no customer data.
- Subdomains of deleted tenants stay taken, so no new tenant inherits the trust of SPs and users in an old host.

### Identifiers

- Random UUIDs, never reused. A user who comes back by the same address gets a new ID, so an SP cannot mistake them for the erased account.
- Free to appear in logs, events, metrics, traces and the erasure journal.
- The erasure journal keeps them for the backup retention plus a margin, under Object Lock.
- A persistent SAML NameID is 256 random bits per user and SP, never the user ID. It goes with the user and with the SP.

### Released to Service Providers

- A Response carries the NameID and the attributes an admin released for that SP, none by default. The fields that are not released are not decrypted.
- An assertion lives five minutes and names one SP as its audience.

## Deleting a tenant

Offboarding waits 30 days after the operator starts it, then destroys the KEK and a previous one left by a move, which makes the tenant's data in every backup unreadable, and drops the schema and its role. The tenant row stays as `DELETED` with its ID and subdomain, and holds no personal data.
