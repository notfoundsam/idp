# Cryptographic inventory

Every key, algorithm and certificate the IdP uses, what each may protect, and how each is created, rotated and destroyed. The lifecycle follows NIST SP 800-57 Part 1. A change that adds a key or an algorithm adds its row here in the same pull request.

## Key policy

- **One purpose per key.** A key protects one kind of data. Where one secret serves several purposes, each purpose takes its own HKDF-SHA-256 output with the purpose and the tenant in the info, so the secret itself encrypts nothing.
- **Generation.** Keys of the application come from `SecureRandom`, KEKs from the KMS. No key is derived from a password or a UUID.
- **Storage.** A KEK never leaves the KMS: AWS KMS keys live in its HSMs, OpenBao transit keys are created without `exportable`. Every other key is stored only wrapped by the key above it, and unwrapped only in memory for at most the cache TTL of `KeyHierarchy`, 5 minutes.
- **Access.** `app`, `worker` and the recovery run of `migrator` after a restore may call `Decrypt` on tenant KEKs, and only `worker` and the recovery run may delete one. `ops` and the CLI may only `Encrypt` with the platform KEK, which only `worker` decrypts. Each service has an IAM role or OpenBao token of its own. In AWS the key policies deny everybody else these calls, see `infra/terraform/README.md`, section Rights in AWS.
- **Cryptoperiod.** A KEK gets a new version every year. The application keys below the KEK live as long as their owner: rotating the tenant key is outside the first version, see [key-compromise-runbook.md](key-compromise-runbook.md).
- **Destruction.** Destroying a key is how data is erased: a user key goes with the user, the tenant KEKs with the tenant. AWS KMS waits 7 days before it deletes key material.
- **Compromise.** See [key-compromise-runbook.md](key-compromise-runbook.md).

## Keys

| Key | Algorithm | Protects | Lives in | Created | Rotated | Destroyed |
|---|---|---|---|---|---|---|
| Tenant KEK | AES-256-GCM (AWS `SYMMETRIC_DEFAULT`, OpenBao `aes256-gcm96`) | The tenant key | KMS, URI in `platform.tenants.kek_uri` | By `worker` while provisioning, in AWS with the key policy `KMS_AWS_KEY_POLICY` | By the KMS every 365 days, old versions keep decrypting. Moved to another KMS by `KekMoveJob` | With the tenant at offboarding. A KEK left by a move goes after `KMS_PREVIOUS_KEK_RETENTION`, 45 days in AWS |
| Platform KEK | Same | Contacts of applications and admin reinvites | KMS, `alias/<env>-platform`, `PLATFORM_KEK_URI` | By `modules/keys` with the key policy of `modules/iam`, locally by `dev-tools/openbao/init.sh` | By the KMS every 365 days, `rotation_period_in_days` as for tenant KEKs | Never while the platform runs |
| Tenant key | 256 random bits | Nothing directly. HKDF-SHA-256 derives the keys below from it | `tenant_keys`, wrapped by the tenant KEK | At provisioning | Not in the first version. `tenant_keys.version` and the Tink key ID leave room for a second version | With the tenant KEK |
| User keys wrapping key | AES-256-GCM, derived from the tenant key | User keys, with the tenant and user in the associated data | Memory only | On use | With the tenant key | With the tenant key |
| User key | Tink AES-256-GCM keyset | Personal data of the user, their TOTP secret, IP and User-Agent of their sessions, IP and details of audit records they made | `user_keys`, wrapped by the key above | When the user is created | Not in the first version | With the user at erasure |
| Flow key | AES-256-GCM, derived from the tenant key | The login typed into a flow, the stored AuthnRequest and its RelayState, with the row ID in the associated data | Memory only | On use | With the tenant key | With the tenant key. The data expires with the flow |
| Audit key | AES-256-GCM, derived from the tenant key | IP and details of audit records by `System` and anonymous actors | Memory only | On use | With the tenant key | With the tenant key |
| Blind index key | HMAC-SHA-256, derived from the tenant key | Lookup of a user by email | Memory only | On use | Not in the first version: the index is computed again from the decrypted addresses | With the tenant key |
| Throttle key | HMAC-SHA-256, keyed by 64 random letters and digits | Client IPs in Valkey counter names | Secrets Manager `<env>/app/throttle-ip-hmac-key`, write-only for Terraform, `THROTTLE_IP_HMAC_KEY` of `app` | By `modules/keys`. The prod profile refuses a key under 32 characters | By raising `credential_versions.throttle_ip_hmac_key`. Counters start over, nothing else depends on it | With the next version |
| SAML signing key wrapping key | AES-256-GCM, derived from the tenant key | SAML signing keys, with the key ID in the associated data | Memory only | On use | With the tenant key | With the tenant key |
| Database storage key | AWS KMS `SYMMETRIC_DEFAULT` | RDS storage, its snapshots and backups, Performance Insights | KMS, `alias/<env>-database` | By Terraform with the instance, which cannot be encrypted later | By the KMS every 365 days | Never while the platform runs |
| Terraform state key | AWS KMS `SYMMETRIC_DEFAULT` | The state of the environment, the internal CA key included | KMS, `alias/<env>-terraform-state` | By `infra/terraform/bootstrap` | By the KMS every 365 days | Never while the environment exists |
| Internal CA key | ECDSA P-384 | Signs the server certificates of internal hosts | The Terraform state only | By `modules/pki` | With the CA after ten years | With the environment |
| PgBouncer TLS key | ECDSA P-256, PKCS#8 | TLS of PgBouncer towards the services | Secrets Manager `<env>/tls/pgbouncer` | By `modules/pki` | With its certificate, by the first apply in its last 60 days | With the next key |
| Database role passwords | 40 random letters and digits, SCRAM-SHA-256 in Postgres | `idp_owner`, `provisioner` and the runtime roles | Secrets Manager `<env>/db/<role>`, write-only for Terraform | By Terraform | By raising `db_password_versions`, see `infra/terraform/README.md` | With the next version |
| Valkey AUTH token | 64 random letters and digits | Access of `app` to ElastiCache | Secrets Manager `<env>/valkey/auth-token`, write-only for Terraform, `VALKEY_PASSWORD` of `app` | By `modules/valkey` | By raising `credential_versions.valkey_auth_token`. ElastiCache accepts the previous token beside it until the next replacement | By the second replacement after it |
| SES SMTP credentials | An IAM access key, and the SigV4 SMTP password SES derives from it | Mail of `worker` from `no-reply@<domain>` alone | Secrets Manager `<env>/mail/smtp` and the Terraform state, `MAIL_USERNAME` and `MAIL_PASSWORD` of `worker` | By `modules/mail` | By raising `credential_versions.smtp`, which deletes the old key in the same apply | With the next version |
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
| Transport | TLS 1.2 or 1.3. A client of an internal service checks the name and trusts one CA alone | Every connection, see [data-protection.md](data-protection.md) and `infra/terraform/README.md` |

## Certificates

| Certificate | Issuer | Where | Renewal |
|---|---|---|---|
| Public TLS of the product domain | ACM | CloudFront and ALB (task 40) | Automatic in ACM |
| Internal CA | Self-signed, valid ten years | `modules/pki`. The services trust it alone for PgBouncer, through `DB_TLS_CA` | A new CA before the old one expires: issue, roll out the new trust, then the new certificates |
| PgBouncer server certificate | The internal CA, for `pgbouncer.idp.internal`, valid one year | PgBouncer | By the first apply in its last 60 days, then a redeploy of PgBouncer |
| RDS server certificate | The RDS CA of `ca_cert_identifier` | RDS. `migrator`, the provisioner and the bootstrap trust only that root of the region, PgBouncer the roots of the region | By RDS. A change of CA changes the pinned root with it |
| ElastiCache server certificate | Amazon, under the roots of Amazon Trust Services | Valkey. `app` trusts only those roots, through `VALKEY_TLS_CA`, and checks the name of the endpoint | By ElastiCache |
| SAML signing certificate of a tenant | Self-signed with `SHA256withRSA` for the tenant host, valid 5 years. SPs pin it from metadata, so its own signature and dates carry no trust | `saml_signing_keys.certificate`, published at `/saml/metadata` | With its key by a rotation |
| SAML certificates of Service Providers | The SP | `service_providers.signing_certificates`, from uploaded metadata | The tenant admin uploads new metadata |
| SAML signing certificate of the service tenant, as `ops` trusts it | The service tenant | The metadata file at `IDP_METADATA_FILE`, never fetched by `ops`. Only RSA-PSS with SHA-256 is accepted | An operator replaces the file during a rotation of the service tenant |

## Rotating a KEK

The KMS rotates a KEK by itself: AWS KMS through `EnableKeyRotation` with 365 days, OpenBao through `auto_rotate_period` of `8760h`. Both adapters set it when they create a key. The URI stays, the tenant key stays wrapped as it is, and old versions keep decrypting, so nothing is wrapped or encrypted again.

## Moving tenants to another KMS

1. Configure the new adapter beside the old one in `app`, `worker` and the recovery run of `migrator`, and point `KMS_NEW_KEYS` of `worker` at it. Both adapters must stay configured until the last previous KEK is destroyed.
2. `KekMoveJob` runs every hour. For every active or suspended tenant whose KEK is in another KMS it creates a KEK in the new one, stores it as `kek_uri` with the old one as `previous_kek_uri`, wraps the tenant key with the new KEK and records `tenant.kek_replaced` in the tenant's journal. Nothing under the tenant key changes.
3. A context made before the move carries the old URI. When that URI fails, `KeyHierarchy` reads both KEKs from `platform.tenants` once and tries the other one.
4. The previous KEK still opens backups taken before the move. `PreviousKekJob` destroys it after `idp.kms.previous-kek-retention` from `KMS_PREVIOUS_KEK_RETENTION`: 35 days by default, as backups live 30, and in AWS 45, as long as the erasure journal keeps an entry. It records `tenant.previous_kek_destroyed`.
5. Offboarding and a restore destroy both KEKs of a tenant.
6. The platform KEK does not move with the tenants: `worker`, `ops`, the CLI and the recovery run each know one URI, `PLATFORM_KEK_URI`, and a contact sealed under it names no KEK. Its data lives briefly, an application contact until the tenant is active and a reinvite until `worker` sends it. Create the platform KEK in the new KMS, let the operators approve the open applications and submit or reinvite nothing until the switch, wait until no row of `platform.tenant_applications` holds a `contact_ciphertext` and `platform.admin_reinvites` is empty, then release the new URI to all four at once. A contact sealed under the old KEK after that cannot be read.

## Replacing an algorithm

1. Add the new algorithm next to the old one. Tink keysets hold several keys with key IDs in the ciphertext prefix, so new data uses the new primary key while old data still decrypts. Password hashes store their parameters and are upgraded at the next login.
2. Make the new algorithm primary in one release, without removing the old one, so a rollback still reads new data.
3. Encrypt or hash existing data again where it outlives the change: user keysets by a job like `KekMoveJob`, passwords at login, tokens and flows by expiry.
4. Remove the old algorithm once no stored data uses it, and update this document.
