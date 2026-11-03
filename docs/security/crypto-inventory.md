# Cryptographic inventory

Every key, algorithm and certificate the IdP uses, what each may protect, and how each is created, rotated and destroyed. The lifecycle follows NIST SP 800-57 Part 1. A change that adds a key or an algorithm adds its row here in the same pull request.

## Key policy

- **One purpose per key.** A key protects one kind of data. Where one secret serves several purposes, each purpose takes its own HKDF-SHA-256 output with the purpose and the tenant in the info, so the secret itself encrypts nothing.
- **Generation.** Keys of the application come from `SecureRandom`, KEKs from the KMS. No key is derived from a password or a UUID.
- **Storage.** A KEK never leaves the KMS: AWS KMS keys live in its HSMs, OpenBao transit keys are created without `exportable`. Every other key is stored only wrapped by the key above it, and unwrapped only in memory for at most the cache TTL of `KeyHierarchy`, 5 minutes.
- **Access.** `app`, `worker` and `migrator` restoring a backup may call `Decrypt` on tenant KEKs. `ops` may only `Encrypt` with the platform KEK. Each service has an IAM role or OpenBao token of its own.
- **Cryptoperiod.** A KEK gets a new version every year. The application keys below the KEK live as long as their owner: rotating the tenant key is outside the first version, see [key-compromise-runbook.md](key-compromise-runbook.md).
- **Destruction.** Destroying a key is how data is erased: a user key goes with the user, the tenant KEKs with the tenant. AWS KMS waits 7 days before it deletes key material.
- **Compromise.** See [key-compromise-runbook.md](key-compromise-runbook.md).

## Keys

| Key | Algorithm | Protects | Lives in | Created | Rotated | Destroyed |
|---|---|---|---|---|---|---|
| Tenant KEK | AES-256-GCM (AWS `SYMMETRIC_DEFAULT`, OpenBao `aes256-gcm96`) | The tenant key | KMS, URI in `platform.tenants.kek_uri` | By `worker` while provisioning | By the KMS every 365 days, old versions keep decrypting. Moved to another KMS by `KekMoveJob` | With the tenant at offboarding. A KEK left by a move goes 35 days later |
| Platform KEK | Same | Contacts of applications and admin reinvites | KMS, `PLATFORM_KEK_URI` | By Terraform, locally by `dev-tools/openbao/init.sh` | By the KMS every 365 days | Never while the platform runs |
| Tenant key | 256 random bits | Nothing directly. HKDF-SHA-256 derives the keys below from it | `tenant_keys`, wrapped by the tenant KEK | At provisioning | Not in the first version. `tenant_keys.version` and the Tink key ID leave room for a second version | With the tenant KEK |
| User keys wrapping key | AES-256-GCM, derived from the tenant key | User keys, with the tenant and user in the associated data | Memory only | On use | With the tenant key | With the tenant key |
| User key | Tink AES-256-GCM keyset | Personal data of the user, their TOTP secret, IP and User-Agent of their sessions, IP and details of audit records they made | `user_keys`, wrapped by the key above | When the user is created | Not in the first version | With the user at erasure |
| Flow key | AES-256-GCM, derived from the tenant key | The login typed into a flow, the stored AuthnRequest and its RelayState, with the row ID in the associated data | Memory only | On use | With the tenant key | With the tenant key. The data expires with the flow |
| Audit key | AES-256-GCM, derived from the tenant key | IP and details of audit records by `System` and anonymous actors | Memory only | On use | With the tenant key | With the tenant key |
| Blind index key | HMAC-SHA-256, derived from the tenant key | Lookup of a user by email | Memory only | On use | Not in the first version: the index is computed again from the decrypted addresses | With the tenant key |
| Throttle key | HMAC-SHA-256 | Client IPs in Valkey counter names | `THROTTLE_IP_HMAC_KEY`, a secret of `app` | With the environment | By replacing the secret. Counters start over, nothing else depends on it | With the secret |
| SAML signing key wrapping key | AES-256-GCM, derived from the tenant key | SAML signing keys, with the key ID in the associated data | Memory only | On use | With the tenant key | With the tenant key |
| SAML signing keys | RSA-3072, PKCS#8 | Responses of a tenant. Signatures use RSA-PSS with SHA-256, or `rsa-sha256` where an SP is set to it, never SHA-1 | `saml_signing_keys`, wrapped by the key above | By `worker` while provisioning | By the tenant admin: a new key is published in metadata first and signs once the rotation completes, the old one is deleted then | With the tenant KEK, a rotated key when the rotation completes |

## Other algorithms

| Use | Algorithm | Where |
|---|---|---|
| Passwords | Argon2id `m=19 MiB, t=2, p=1`, 16-byte salt, parameters stored with the hash | `PasswordHasher` |
| Session, invitation, reset and email change tokens | 256 random bits, stored as SHA-256 | `SsoSession`, `OneTimeToken` |
| Recovery codes | 128 random bits, stored as SHA-256 | `RecoveryCode` |
| CSRF token | HMAC-SHA-256 keyed by the session token | `CsrfCookie` |
| TOTP | HMAC-SHA-1 with a 160-bit secret, an ASVS exception | `TotpCredential` |
| Passkey signatures | Verification of ES256, EdDSA and RS256 | `PasskeyVerifier` |
| SAML signatures | RSA-PSS with SHA-256 by default, `rsa-sha256` per SP, SHA-256 digests, exclusive C14N, through OpenSAML and Apache Santuario | `SignatureAlgorithm` |
| Field encryption | AES-256-GCM through Tink, the field and the row in the associated data | `FieldCipher` |
| Transport | TLS | Every connection, see [data-protection.md](data-protection.md) |

## Certificates

| Certificate | Issuer | Where | Renewal |
|---|---|---|---|
| Public TLS of the product domain | ACM | CloudFront and ALB (task 40) | Automatic in ACM |
| SAML signing certificate of a tenant | Self-signed with `SHA256withRSA` for the tenant host, valid 5 years. SPs pin it from metadata, so its own signature and dates carry no trust | `saml_signing_keys.certificate`, published at `/saml/metadata` | With its key by a rotation |
| SAML certificates of Service Providers | The SP | `service_providers.signing_certificates`, from uploaded metadata | The tenant admin uploads new metadata |

## Rotating a KEK

The KMS rotates a KEK by itself: AWS KMS through `EnableKeyRotation` with 365 days, OpenBao through `auto_rotate_period` of `8760h`. Both adapters set it when they create a key. The URI stays, the tenant key stays wrapped as it is, and old versions keep decrypting, so nothing is wrapped or encrypted again.

## Moving tenants to another KMS

1. Configure the new adapter beside the old one in `app`, `worker` and `migrator`, and point `KMS_NEW_KEYS` of `worker` at it. Both adapters must stay configured until the last previous KEK is destroyed.
2. `KekMoveJob` runs every hour. For every active or suspended tenant whose KEK is in another KMS it creates a KEK in the new one, stores it as `kek_uri` with the old one as `previous_kek_uri`, wraps the tenant key with the new KEK and records `tenant.kek_replaced` in the tenant's journal. Nothing under the tenant key changes.
3. A context made before the move carries the old URI. When that URI fails, `KeyHierarchy` reads both KEKs from `platform.tenants` once and tries the other one.
4. The previous KEK still opens backups taken before the move. `PreviousKekJob` destroys it after `idp.kms.previous-kek-retention`, 35 days: backups live 30. It records `tenant.previous_kek_destroyed`.
5. Offboarding and a restore destroy both KEKs of a tenant.

## Replacing an algorithm

1. Add the new algorithm next to the old one. Tink keysets hold several keys with key IDs in the ciphertext prefix, so new data uses the new primary key while old data still decrypts. Password hashes store their parameters and are upgraded at the next login.
2. Make the new algorithm primary in one release, without removing the old one, so a rollback still reads new data.
3. Encrypt or hash existing data again where it outlives the change: user keysets by a job like `KekMoveJob`, passwords at login, tokens and flows by expiry.
4. Remove the old algorithm once no stored data uses it, and update this document.
