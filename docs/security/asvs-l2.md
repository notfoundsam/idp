# OWASP ASVS Level 2 map

Every Level 1 and Level 2 requirement of [OWASP ASVS 5.0.0](https://github.com/OWASP/ASVS/tree/v5.0.0_release) mapped to its control. ASVS is published under CC BY-SA 4.0. Topics below are short paraphrases, so read the standard for the exact wording.

The scope is `app`, `worker`, `ops`, `migrator`, the frontend and the production infrastructure. Level 3 requirements are out of scope.

## Status

| Status | Meaning | Count |
|---|---|---|
| Done | In merged code, proven by the named test, or by inspection where there is no behaviour to test | 68 |
| Partial | Part of the control is merged, the listed tasks finish it | 60 |
| Planned | The listed tasks build the control | 54 |
| N/A | The feature does not exist, the note says why | 67 |
| Exception | Deliberate deviation, see [Exceptions](#exceptions) | 4 |

`T17` is the issue titled `Task 17: ...`. The application reaches Level 2 when no row is Partial or Planned.

## Maintenance

A change that adds, removes or weakens a control updates its row in the same pull request. A new feature that makes an N/A row applicable changes that row too, for example a file upload, a WebSocket endpoint or OIDC.

## Exceptions

| Requirement | Deviation | Reason | Limit |
|---|---|---|---|
| V11.6.1 | `rsa-sha256` (RSASSA-PKCS1-v1_5) signs SAML messages for a Service Provider that accepts nothing else | Appendix C disallows this scheme, but many SAML products support no other | The default is an approved scheme. The legacy scheme is an explicit per-SP setting, is audited, and never uses SHA-1 |
| V11.6.1 | Passkeys may verify `RS256` signatures | Some platform authenticators offer only this algorithm | Verification only, the application never creates such signatures |
| V11.4.1 | TOTP uses HMAC-SHA-1 | RFC 6238 authenticator apps implement this function. Appendix C lists it as legacy, not disallowed | TOTP only, every other use of SHA-1 is refused |
| V11.2.2 | The tenant key and the keys below it are not replaced in place | Rotating them means wrapping every user key again and computing the blind index again, which is outside the first version | KEKs above them rotate in the KMS and move between KMS. A compromised tenant key is replaced as the [runbook](key-compromise-runbook.md) says, and `tenant_keys.version` leaves room for a second key |
| V6.3.3 | A tenant may relax MFA for the `USER` role | Customers decide this for their own workforce | MFA is on by default and cannot be relaxed for `TENANT_ADMIN`. Relaxing is audited, and a tenant that does so falls outside this Level 2 claim |

## V1 Encoding and Sanitization

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V1.1.1 | 2 | Partial | T28 | JSON and base64url are decoded once, before validation (`FlowApiTest`), SAML base64 and DEFLATE remain |
| V1.1.2 | 2 | Partial | T29, T31 | Encoding at the sink: mail templates and jOOQ are merged, SAML form and SPA remain |
| V1.2.1 | 1 | Partial | T29, T31 | HTML mail is escaped (`MailTemplatesTest`), SAML form and SPA remain |
| V1.2.2 | 1 | Done | T27 | Mail links are `https` and built from the stored tenant host, never from a request (`RecoveryMailTest`). ACS URLs must be absolute `https` without user info or fragment, so `javascript:` and `data:` are refused (`ServiceProviderTest`, `MetadataImporterTest`) |
| V1.2.3 | 1 | Partial | T31 | JSON comes only from the serializer (`FlowContractTest`), the frontend remains |
| V1.2.4 | 1 | Done | T4, T5 | jOOQ binds every value, DDL identifiers derive only from a tenant UUID (`TenantIsolationTest`) |
| V1.2.5 | 1 | N/A | | The application starts no OS processes |
| V1.2.6 | 2 | N/A | | No LDAP |
| V1.2.7 | 2 | N/A | | No XPath is built from input, OpenSAML reads the object model |
| V1.2.8 | 2 | N/A | | No LaTeX |
| V1.2.9 | 2 | N/A | | Regular expressions are constants, input never becomes a pattern |
| V1.3.1 | 1 | N/A | | No rich text input |
| V1.3.2 | 1 | Partial | T31 | The backend has no `eval` or SpEL, CSP without `unsafe-eval` covers the SPA |
| V1.3.3 | 2 | Partial | T28 | Control characters are stripped from mail (`MailTemplatesTest`), RelayState has a length limit |
| V1.3.4 | 2 | N/A | | No user-supplied SVG |
| V1.3.5 | 2 | N/A | | No user-supplied Markdown, CSS or XSL |
| V1.3.6 | 2 | N/A | | No request goes to a user-supplied URL: SP metadata comes from a file, KMS addresses from configuration |
| V1.3.7 | 2 | Done | T12 | Mail templates are files in the jar, input only fills values (`MailTemplatesTest`) |
| V1.3.8 | 2 | N/A | | No JNDI lookups |
| V1.3.9 | 2 | Done | T11 | Valkey keys hold a tenant ID and an HMAC of the IP, never raw input (`ThrottleTest`) |
| V1.3.10 | 2 | N/A | | No format string comes from input |
| V1.3.11 | 2 | Done | T12 | Mail fields go through `MimeMessageHelper`, control characters are removed (`MailTemplatesTest`) |
| V1.4.1 | 2 | N/A | | JVM and JavaScript are memory safe, the application has no native code of its own |
| V1.4.2 | 2 | Planned | T36 | Numeric API inputs have ranges in the contracts |
| V1.4.3 | 2 | N/A | | Garbage-collected runtimes |
| V1.5.1 | 1 | Partial | T28, T30 | XML parsers refuse DTDs and external entities, with negative tests. The metadata parser refuses DTDs, external entities and XInclude (`MetadataImporterTest`), AuthnRequest and the ops SP remain |
| V1.5.2 | 2 | Done | T8 | Jackson binds fixed types without default typing, no Java serialization (`OutboxDispatcherTest`) |

## V2 Validation and Business Logic

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V2.1.1 | 1 | Planned | T36 | `docs/security/input-validation.md` and the OpenAPI contracts define input rules |
| V2.1.2 | 2 | Planned | T36 | The same document covers combined data items |
| V2.1.3 | 2 | Done | T23 | `docs/security/limits.md` lists per-user and global limits |
| V2.2.1 | 1 | Partial | T32, T34, T36 | Domain types validate on the server (`TenantTest`), the login API refuses malformed input (`FlowApiTest`), each API adds its inputs |
| V2.2.2 | 1 | Partial | T32, T34, T36 | Validation lives in the domain and the audience, client checks are convenience only |
| V2.2.3 | 2 | Planned | T34, T36 | Policy and SP settings are checked as a combination |
| V2.3.1 | 1 | Done | T23 | `AuthFlow` decides the next step and refuses any other (`AuthFlowTest`), the API cannot skip one (`FlowApiTest`) |
| V2.3.2 | 2 | Done | T23 | The limits from `docs/security/limits.md` are enforced (`LoginLimitsTest`, `LoginThrottleConfigTest`, `SessionsTest`) |
| V2.3.3 | 2 | Done | T8, T11, T13 | One transaction per operation, with state and event together (`BusDecoratorsTest`, `OutboxDispatcherTest`, `VersionedUpdateTest`) |
| V2.3.4 | 2 | Done | T23, T24 | Invitation, reset and email change tokens and recovery codes are consumed by a conditional delete (`IdentityServiceTest`, `AccountRecoveryTest`, `TotpServiceTest`), TOTP steps by a conditional update (`TotpServiceTest`), flow steps by a lease and a version (`FlowApiTest`, `SessionsTest`) |
| V2.4.1 | 2 | Partial | T28, T40 | The throttle decorator and the limits of the login are merged (`ThrottleTest`, `LoginLimitsTest`), SAML filter and WAF remain |

## V3 Web Frontend Security

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V3.2.1 | 1 | Partial | T29 | The API answers JSON with `nosniff` and a closed CSP (`ResponseHeadersTest`, `OpsApiTest`), the SAML form remains. Uploaded files are never served |
| V3.2.2 | 1 | Planned | T31 | Vue interpolation only, a lint rule forbids `v-html` |
| V3.3.1 | 1 | Done | T23 | Every cookie is `Secure` (`ResponseHeadersTest`) |
| V3.3.2 | 2 | Done | T23 | The session cookie is `SameSite=Lax`, the flow and CSRF cookies `SameSite=Strict` (`SessionCookieTest`, `FlowCookieTest`, `CsrfCookieTest`, `ResponseHeadersTest`) |
| V3.3.3 | 2 | Done | T23 | Every cookie uses `__Host-` (`ResponseHeadersTest`) |
| V3.3.4 | 2 | Done | T22 | The session cookie is `HttpOnly` and travels only in `Set-Cookie` (`SessionCookieTest`) |
| V3.4.1 | 1 | Partial | T31, T40 | HSTS with one year and `includeSubDomains` on every response of `app` and `ops` (`ResponseHeadersTest`, `OpsApiTest`), static files remain |
| V3.4.2 | 1 | Done | T23 | The API is same-origin and sends no CORS headers (`ResponseHeadersTest`) |
| V3.4.3 | 2 | Partial | T29, T31, T40 | CSP with `object-src 'none'` and `base-uri 'none'` on the API (`ResponseHeadersTest`), nonce on the SAML form and static files remain |
| V3.4.4 | 2 | Partial | T31, T40 | `nosniff` on every response of `app` and `ops` (`ResponseHeadersTest`, `OpsApiTest`), static files remain |
| V3.4.5 | 2 | Partial | T31, T40 | `Referrer-Policy` on every response of `app` and `ops` (`ResponseHeadersTest`, `OpsApiTest`), static files remain |
| V3.4.6 | 2 | Partial | T31, T40 | `frame-ancestors 'none'` on every response of `app` and `ops` (`ResponseHeadersTest`, `OpsApiTest`), static files remain |
| V3.5.1 | 1 | Done | T23 | An anti-forgery token derived from the session guards every unsafe call of a live session (`SessionGuardsTest`, `CsrfCookieTest`) |
| V3.5.2 | 1 | N/A | | Sensitive calls rely on tokens, not on CORS preflight |
| V3.5.3 | 1 | Partial | T29, T34 | State changes of the login use `POST` (`FlowContractTest`), the SAML resume `GET` and the admin API remain |
| V3.5.4 | 2 | Partial | T30, T40 | Each tenant has its own host (`TenantFilterTest`), `ops` is separate. Login and console form one application |
| V3.5.5 | 2 | Planned | T31 | No `postMessage` listeners, enforced by lint |
| V3.7.1 | 2 | Planned | T31 | Only supported browser technology, no plugins |
| V3.7.2 | 2 | Partial | T28, T29 | The only automatic cross-host navigation goes to a registered ACS URL. ACS URLs are registered from metadata as `https` only (`MetadataImporterTest`), sending the Response remains |

## V4 API and Web Service

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V4.1.1 | 1 | Partial | T29, T31 | Every JSON body and the SAML metadata name their charset (`ResponseHeadersTest`, `OpsApiTest`, `MetadataApiTest`), HTML remains |
| V4.1.2 | 2 | Planned | T40 | Only `/login/*` and `/console/*` redirect to HTTPS, `/api/*` and `/saml/*` refuse plain HTTP |
| V4.1.3 | 2 | Partial | T40, T41 | `app` trusts one configured header (`ClientIpTest`), the edge must overwrite it |
| V4.2.1 | 2 | Planned | T40 | The load balancer drops invalid headers and uses strict desync mitigation |
| V4.3.1 | 2 | N/A | | No GraphQL |
| V4.3.2 | 2 | N/A | | No GraphQL |
| V4.4.1 | 1 | N/A | | No WebSocket |
| V4.4.2 | 2 | N/A | | No WebSocket |
| V4.4.3 | 2 | N/A | | No WebSocket |
| V4.4.4 | 2 | N/A | | No WebSocket |

## V5 File Handling

SP metadata import is the only file upload.

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V5.1.1 | 2 | Planned | T36 | `docs/security/input-validation.md` describes the upload |
| V5.2.1 | 1 | Done | T27 | Metadata over 128 KiB is refused before parsing (`MetadataImporterTest`) |
| V5.2.2 | 1 | Done | T27 | Content must be one `EntityDescriptor` with an SP role that validates against the SAML metadata schema (`MetadataImporterTest`) |
| V5.2.3 | 2 | N/A | | Compressed files are not accepted. SAML DEFLATE limits are under V15.2.2 |
| V5.3.1 | 1 | N/A | | Uploads are parsed into the database, no file lands in a served folder |
| V5.3.2 | 1 | Partial | T36 | The erasure journal builds paths from IDs and refuses any other name (`FileErasureJournalTest`, `S3ErasureJournalLocalStackTest`). Metadata import takes the content only and has no parameter for a file name (`FederationService`), the upload endpoint remains |
| V5.4.1 | 2 | Done | T27 | Metadata downloads use the fixed name `idp-metadata.xml` in `Content-Disposition`, whatever the request holds (`MetadataApiTest`) |
| V5.4.2 | 2 | N/A | | Served file names are constants, mail has no attachments |
| V5.4.3 | 2 | N/A | | No untrusted file is served back to anyone |

## V6 Authentication

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V6.1.1 | 1 | Done | T23 | `docs/security/authentication.md` covers rate limits and malicious lockout |
| V6.1.2 | 2 | Done | T23 | Context-specific words are refused (`PasswordPolicyTest`) and listed in the same document |
| V6.1.3 | 2 | Done | T23 | The same document lists every pathway with its strength |
| V6.2.1 | 1 | Partial | T31, T33 | At least 8 characters (`PasswordPolicyTest`), the UI hint for 15 remains |
| V6.2.2 | 1 | Planned | T32 | Users change their password in the account area |
| V6.2.3 | 1 | Planned | T32 | The change requires the current password |
| V6.2.4 | 1 | Done | T18 | Top passwords are refused (`PasswordPolicyTest`, `BannedPasswordsTest`) |
| V6.2.5 | 1 | Done | T18 | No composition rules (`PasswordPolicyTest`) |
| V6.2.6 | 1 | Planned | T31 | `type=password` with an optional reveal |
| V6.2.7 | 1 | Planned | T31 | Paste and password managers work |
| V6.2.8 | 1 | Done | T18 | The password is verified exactly as received (`PasswordHasherTest`) |
| V6.2.9 | 2 | Done | T18 | Up to 128 characters are accepted (`PasswordPolicyTest`) |
| V6.2.10 | 2 | Partial | T34 | A credential has no expiry (`PasswordCredential`), the test that the policy offers none remains |
| V6.2.11 | 2 | Done | T18 | Context-specific words are refused (`PasswordPolicyTest`, `PasswordServiceTest`) |
| V6.2.12 | 2 | Done | T18 | Breached passwords are refused using an offline list (`PasswordPolicyTest`, `BannedPasswordsTest`) |
| V6.3.1 | 1 | Done | T23 | Growing delay and attempt counters in Postgres, which hold without the throttle (`PasswordServiceTest`, `TotpServiceTest`, `LoginLimitsTest`), and limits by address and tenant (`LoginLimitsTest`, `LoginThrottleConfigTest`) |
| V6.3.2 | 1 | Done | T14, T17 | Seed accounts exist only in the `local` profile (`LocalSeedTest`), the first admin is invited (`TenantProvisioningProcessTest`) |
| V6.3.3 | 2 | Exception | T34 | MFA by default, a passkey with user verification counts as MFA, and every new tenant stores this policy (`AuthFlowTest`, `TenantPolicyTest`, `SessionsTest`), its relaxing remains. See [Exceptions](#exceptions) |
| V6.3.4 | 2 | Done | T23 | Every session starts from `AuthFlow`, and `docs/security/authentication.md` lists every pathway |
| V6.4.1 | 1 | Done | T24 | Invitations are random, short-lived and one-time (`InvitationTest`, `IdentityServiceTest`), and the first password is chosen by the user under the password policy (`RecoveryCommandsTest`, `RecoveryApiTest`) |
| V6.4.2 | 1 | N/A | | No password hints or secret questions |
| V6.4.3 | 2 | Done | T24 | A reset never opens a session, and the next login still asks for the second factor (`RecoveryApiTest`, `RecoveryCommandsTest`) |
| V6.4.4 | 2 | Partial | T34 | A lost factor comes back only through `IdentityService.resetMfa`, and the user enrolls again (`AccountRecoveryTest`, `RecoveryApiTest`). An only admin goes through the [runbook](tenant-admin-recovery-runbook.md) (`ReinviteAdminHandlerTest`). The admin command remains |
| V6.5.1 | 2 | Done | T19 | TOTP codes and recovery codes work once (`TotpServiceTest`) |
| V6.5.2 | 2 | Done | T19 | Recovery codes have 128 bits of entropy and are stored as SHA-256 (`RecoveryCodeTest`, `TotpServiceTest`) |
| V6.5.3 | 2 | Done | T19 | Codes and seeds come from a CSPRNG (`RecoveryCodeTest`, `TotpCredentialTest`) |
| V6.5.4 | 2 | Done | T19 | Recovery codes carry 128 bits of entropy (`RecoveryCodeTest`) |
| V6.5.5 | 2 | Done | T19 | TOTP step of 30 seconds (`TotpCredentialTest`), the tolerance is in `docs/security/authentication.md` |
| V6.6.1 | 2 | N/A | | No SMS or phone authentication |
| V6.6.2 | 2 | N/A | | No out-of-band factor, mail links serve recovery only |
| V6.6.3 | 2 | N/A | | No out-of-band factor |
| V6.8.1 | 2 | N/A | | `ops` trusts a single identity provider, `app` trusts none |
| V6.8.2 | 2 | Planned | T28, T30 | `ops` refuses unsigned or invalid assertions, the IdP checks signed requests |
| V6.8.3 | 2 | Planned | T28, T30 | Request IDs and `ops` assertion IDs are used once |
| V6.8.4 | 2 | Planned | T29, T30 | The IdP states the real authentication context, `ops` requires a passkey context |

## V7 Session Management

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V7.1.1 | 2 | Done | T22 | `docs/security/sessions.md` justifies timeouts against NIST SP 800-63B |
| V7.1.2 | 2 | Done | T22 | The same document defines concurrent sessions, the limit holds (`SessionsTest`) |
| V7.1.3 | 2 | Partial | T29 | The same document covers the IdP session and SP sessions, `SessionNotOnOrAfter` remains |
| V7.2.1 | 1 | Done | T22 | `resolveSession` checks tokens on the backend (`SessionsTest`, `SessionFilterTest`) |
| V7.2.2 | 1 | Done | T22 | Tokens are generated per session (`SessionsTest`) |
| V7.2.3 | 1 | Done | T22 | Reference tokens with 256 bits from a CSPRNG, not UUIDs (`SsoSessionTest`) |
| V7.2.4 | 1 | Done | T22 | A new token on login and on re-authentication (`SessionsTest`) |
| V7.3.1 | 2 | Done | T22 | Idle timeout from tenant policy (`SessionsTest`, `SsoSessionTest`) |
| V7.3.2 | 2 | Done | T22 | Absolute timeout from tenant policy, a continued session keeps its login time (`SessionsTest`, `SsoSessionTest`) |
| V7.4.1 | 1 | Planned | T32 | Logout invalidates the session on the server |
| V7.4.2 | 1 | Partial | T34 | A blocked user is refused on the next request, erasing a user deletes their sessions and flows with the row, and revoking joins the caller's transaction (`SessionsTest`, `SessionFilterTest`), the handlers that block and erase remain |
| V7.4.3 | 2 | Partial | T32 | A password reset ends every session of the user (`RecoveryCommandsTest`, `RecoveryApiTest`), a factor change offering the same remains |
| V7.4.4 | 2 | Planned | T33 | Logout is visible on every authenticated page |
| V7.4.5 | 2 | Planned | T34 | Admins end sessions of one user or of everyone |
| V7.5.1 | 2 | Partial | T32 | The bus refuses a message marked `RequiresFreshAuthentication` after 10 minutes (`FreshAuthenticationTest`, `SessionGuardsTest`), asking to change the email carries that mark and only a flow with `reauthenticate` refreshes it (`RecoveryApiTest`), the factor messages remain |
| V7.5.2 | 2 | Planned | T32, T33 | Users see their sessions and end them after authenticating again |
| V7.6.1 | 2 | Planned | T29, T30 | SP session bounds are stated in the assertion and follow the document |
| V7.6.2 | 2 | Planned | T28, T29 | Sessions start only from a user action: SSO is SP-initiated, IdP pages cannot be framed |

## V8 Authorization

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V8.1.1 | 1 | Planned | T34 | `docs/security/authorization.md` defines function and data rules |
| V8.1.2 | 2 | Planned | T34 | The same document defines field-level rules |
| V8.2.1 | 1 | Partial | T32, T34, T36 | The bus denies by default (`BusDecoratorsTest`, `ArchitectureRulesTest`, `FailureResponsesTest`), and each public login message is listed in `ArchitectureRules`, each other audience adds its rules |
| V8.2.2 | 1 | Planned | T32, T34, T36 | Handlers take the user from the context and check ownership |
| V8.2.3 | 2 | Partial | T32, T34, T36 | `ops` reads only the masked contact (`OpsAccessBoundaryTest`), responses belong to their audience |
| V8.3.1 | 1 | Partial | T32, T34, T36 | Authorization runs on the bus on the server (`BusDenialTest`), each audience adds its rules |
| V8.4.1 | 2 | Partial | T31, T34, T36 | Database roles isolate tenants (`TenantIsolationTest`), API and e2e isolation tests remain |

## V9 Self-contained Tokens

SAML assertions are the self-contained tokens. The IdP issues them and `ops` consumes them.

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V9.1.1 | 1 | Planned | T28, T30 | Signatures are verified before content is used |
| V9.1.2 | 1 | Partial | T30 | Allowlist of signature algorithms, SHA-1 is refused. An SP setting names RSA-PSS or RSA with SHA-256 only, SHA-1 URIs are refused (`ServiceProviderTest`, `SignatureAlgorithmTest`), the ops SP remains |
| V9.1.3 | 1 | Partial | T30 | Keys come from pre-registered metadata, never from the message. SP certificates are stored from uploaded metadata (`FederationServiceTest`), the ops SP remains |
| V9.2.1 | 1 | Planned | T29, T30 | Validity window is issued and verified |
| V9.2.2 | 2 | Planned | T30 | `ops` accepts only a bearer assertion for its own SSO |
| V9.2.3 | 2 | Planned | T29, T30 | Audience is issued and verified |
| V9.2.4 | 2 | Partial | T29 | Each assertion names one SP, EntityID is unique within a tenant. Uniqueness is merged (`FederationServiceTest`), the audience of the assertion remains |

## V10 OAuth and OIDC

OAuth and OIDC are not part of the first version. This chapter must be revisited when they arrive.

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V10.1.1 | 2 | N/A | | No OAuth |
| V10.1.2 | 2 | N/A | | No OAuth |
| V10.2.1 | 2 | N/A | | No OAuth |
| V10.2.2 | 2 | N/A | | No OAuth |
| V10.3.1 | 2 | N/A | | No OAuth |
| V10.3.2 | 2 | N/A | | No OAuth |
| V10.3.3 | 2 | N/A | | No OAuth |
| V10.3.4 | 2 | N/A | | No OAuth |
| V10.4.1 | 1 | N/A | | No OAuth |
| V10.4.2 | 1 | N/A | | No OAuth |
| V10.4.3 | 1 | N/A | | No OAuth |
| V10.4.4 | 1 | N/A | | No OAuth |
| V10.4.5 | 1 | N/A | | No OAuth |
| V10.4.6 | 2 | N/A | | No OAuth |
| V10.4.7 | 2 | N/A | | No OAuth |
| V10.4.8 | 2 | N/A | | No OAuth |
| V10.4.9 | 2 | N/A | | No OAuth |
| V10.4.10 | 2 | N/A | | No OAuth |
| V10.4.11 | 2 | N/A | | No OAuth |
| V10.5.1 | 2 | N/A | | No OIDC |
| V10.5.2 | 2 | N/A | | No OIDC |
| V10.5.3 | 2 | N/A | | No OIDC |
| V10.5.4 | 2 | N/A | | No OIDC |
| V10.5.5 | 2 | N/A | | No OIDC |
| V10.6.1 | 2 | N/A | | No OIDC |
| V10.6.2 | 2 | N/A | | No OIDC |
| V10.7.1 | 2 | N/A | | No OAuth |
| V10.7.2 | 2 | N/A | | No OAuth |
| V10.7.3 | 2 | N/A | | No OAuth |

## V11 Cryptography

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V11.1.1 | 2 | Done | T26 | [crypto-inventory.md](crypto-inventory.md) holds the key policy and lifecycle after NIST SP 800-57, and the [runbook](key-compromise-runbook.md) covers a compromise. Rotation and moves of KEKs are tested (`KeyRotationJobsTest`, `KeyManagementServiceContract`) |
| V11.1.2 | 2 | Done | T26, T27 | The same document inventories keys, algorithms and certificates and where each may be used, SAML keys and certificates included |
| V11.2.1 | 2 | Done | T27 | Tink, the JDK, KMS, Bouncy Castle, WebAuthn4J and OpenSAML with Apache Santuario (`SignatureAlgorithmTest`) |
| V11.2.2 | 2 | Exception | T26 | Ciphertexts carry a key version in the Tink prefix, password hashes carry their parameters (`PasswordHasherTest`), and [crypto-inventory.md](crypto-inventory.md) describes replacing an algorithm. KEKs rotate in the KMS and move to another KMS without encrypting data again (`KeyRotationJobsTest`). See [Exceptions](#exceptions) for the tenant key |
| V11.2.3 | 2 | Done | T27 | AES-256, HMAC-SHA-256 and RSA-3072 SAML keys, a weaker or non-RSA signing key is refused (`SigningKeyTest`, `SigningKeyGeneratorTest`) |
| V11.3.1 | 1 | Done | T7 | Only AES-GCM through Tink and KMS envelopes (`FieldCipherTest`) |
| V11.3.2 | 1 | Done | T7 | Only AES-GCM (`FieldCipherTest`, `PlatformCipherTest`) |
| V11.3.3 | 2 | Done | T7 | Authenticated encryption with AAD bound to the row and field (`FieldCipherTest`) |
| V11.4.1 | 1 | Exception | T29 | SHA-256 family everywhere except TOTP (`TotpCredential`), the SAML digest is fixed to SHA-256 (`ServiceProviderTest`). See [Exceptions](#exceptions) |
| V11.4.2 | 2 | Done | T18 | Argon2id with `m=19 MiB, t=2, p=1`, an Appendix C value (`PasswordHasherTest`) |
| V11.4.3 | 2 | Partial | T29 | HMAC-SHA-256 and HKDF-SHA-256 are merged (`BlindIndexTest`, `KeyHierarchyTest`), SAML digests of the Response remain |
| V11.4.4 | 2 | N/A | | No key is derived from a password |
| V11.5.1 | 2 | Done | T24 | Key material, session tokens, TOTP secrets, recovery codes and every token in a mail use `SecureRandom`, with at least 128 bits and never a UUID (`InvitationTest`, `AccountRecoveryTest`, `SsoSessionTest`, `TotpCredentialTest`, `RecoveryCodeTest`) |
| V11.6.1 | 2 | Exception | T29 | RSA-PSS with SHA-256 by default, `rsa-sha256` only as an audited setting of one SP (`ServiceProviderTest`, `FederationServiceTest`, `SignatureAlgorithmTest`), passkeys accept only ES256, EdDSA and RS256 (`PasskeyServiceTest`). See [Exceptions](#exceptions) |

## V12 Secure Communication

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V12.1.1 | 1 | Partial | T39, T40, T41 | SMTP accepts only TLS 1.2 and 1.3 (`WorkerApplicationTest`), the other connections remain |
| V12.1.2 | 2 | Planned | T40, T41 | Edge and load balancer security policies with recommended suites |
| V12.1.3 | 2 | N/A | | No mTLS |
| V12.2.1 | 1 | Planned | T40 | HTTPS only at the edge |
| V12.2.2 | 1 | Planned | T40 | Public certificates from ACM |
| V12.3.1 | 2 | Partial | T39, T41 | SMTP requires STARTTLS, database, Valkey, telemetry and targets remain |
| V12.3.2 | 2 | Partial | T39, T41 | SMTP verifies the server name (`WorkerApplicationTest`), `verify-full` for Postgres and the other clients remain |
| V12.3.3 | 2 | Planned | T41 | TLS between the load balancer and services |
| V12.3.4 | 2 | Planned | T39, T41 | Internal certificates come from a pinned private CA |

## V13 Configuration

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V13.1.1 | 2 | Planned | T41 | `docs/security/communications.md` lists every connection |
| V13.2.1 | 2 | Partial | T39, T41 | Each service has its own database role (`DatabaseRolesTest`), IAM roles remain |
| V13.2.2 | 2 | Partial | T39, T41 | Roles have per-table grants (`DatabaseRolesTest`, `OpsAccessBoundaryTest`), IAM remains |
| V13.2.3 | 2 | Planned | T39 | Generated credentials in production, default ones stay in the local environment |
| V13.2.4 | 2 | Planned | T41 | Security groups allow only listed destinations |
| V13.2.5 | 2 | Planned | T41 | The same egress allowlist |
| V13.3.1 | 2 | Planned | T39, T41 | Secrets Manager and KMS, no secrets in source or images |
| V13.3.2 | 2 | Planned | T41 | One IAM role per service, `ops` has no `Decrypt` |
| V13.4.1 | 1 | Done | T2 | `.dockerignore` admits only built jars |
| V13.4.2 | 2 | Partial | T41 | JDWP exists only in local compose, production task definitions must prove it |
| V13.4.3 | 2 | Planned | T31, T40 | No listing in nginx or S3 |
| V13.4.4 | 2 | Done | T23 | `TRACE` is refused (`ResponseHeadersTest`, `OpsApiTest`) |
| V13.4.5 | 2 | Partial | T41 | Management port exposes only health (`AppApplicationTest`), it must stay private |

## V14 Data Protection

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V14.1.1 | 2 | Done | T25 | [data-protection.md](data-protection.md) classifies data, by inspection |
| V14.1.2 | 2 | Done | T25 | The same document sets encryption, integrity, retention, logging and access per class, by inspection |
| V14.2.1 | 1 | Partial | T28, T31 | Every mail token travels in the URL fragment and reaches the API in a request body (`TenantProvisioningProcessTest`, `RecoveryMailTest`, `RecoveryApiTest`). SAML redirect parameters remain |
| V14.2.2 | 2 | Planned | T40 | The edge does not cache `/api/*` and `/saml/*`, the application has no response cache |
| V14.2.3 | 2 | Planned | T31 | No third-party scripts, enforced by CSP |
| V14.2.4 | 2 | Partial | T38 | Field encryption, audit, users, sessions, erasure and its reapplication after a restore are merged (`FieldCipherTest`, `AuditTest`, `IdentityServiceTest`, `SessionsTest`, `ErasureTest`, `ReapplyErasuresJobTest`), logs remain |
| V14.3.1 | 1 | Planned | T32, T33 | `Clear-Site-Data` on logout, and the client clears itself when the server is unreachable |
| V14.3.2 | 2 | Done | T23 | `Cache-Control: no-store` on all `/api/*` (`ResponseHeadersTest`, `OpsApiTest`) |
| V14.3.3 | 2 | Planned | T31 | Nothing sensitive in browser storage |

## V15 Secure Coding and Architecture

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V15.1.1 | 1 | Planned | T42 | `docs/security/dependencies.md` defines remediation time frames |
| V15.1.2 | 2 | Partial | T42 | The dependency graph reaches GitHub from CI, a release SBOM remains |
| V15.1.3 | 2 | Done | T23 | `docs/security/limits.md` lists expensive operations |
| V15.2.1 | 1 | Partial | T42 | Renovate and dependency review run, the time frames remain |
| V15.2.2 | 2 | Partial | T28 | Throttling, the hash semaphore and the limits on flows and request size are merged (`HashLimitTest`, `LoginLimitsTest`), a DEFLATE size limit remains |
| V15.2.3 | 2 | Partial | T41 | Seed runs only in `local` (`LocalSeedTest`), production must never set that profile |
| V15.3.1 | 1 | Partial | T32, T34, T36 | Audiences own responses (`OpsApiTest`, `FlowContractTest`), each API adds its own |
| V15.3.2 | 2 | Done | T6 | The OpenBao client keeps the JDK default of not following redirects, by inspection |
| V15.3.3 | 2 | Done | T23 | Requests list their fields, unknown fields are refused (`FlowApiTest`, `OpsApiTest`) |
| V15.3.4 | 2 | Partial | T40, T41 | One trusted header (`ClientIpTest`), the edge must set it |
| V15.3.5 | 2 | Partial | T31 | Kotlin is statically typed, the frontend needs strict TypeScript and `eqeqeq` |
| V15.3.6 | 2 | Planned | T31 | `Map` and `Set` for keyed data, enforced by lint |
| V15.3.7 | 2 | Partial | T28 | Each value of the API has one declared source (`FlowApiTest`), repeated SAML parameters remain |

## V16 Security Logging and Error Handling

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V16.1.1 | 2 | Planned | T38 | `docs/security/logging.md` holds the log inventory |
| V16.2.1 | 2 | Partial | T38 | Audit records actor, action, target, IP and time (`AuditTest`), application logs remain |
| V16.2.2 | 2 | Partial | T38 | Audit stores instants, logs need UTC |
| V16.2.3 | 2 | Planned | T38 | Logs go only to the listed destinations |
| V16.2.4 | 2 | Planned | T38 | Structured JSON logs |
| V16.2.5 | 2 | Partial | T38 | The bus logs no payloads (`BusDecoratorsTest`), request spans carry the tenant ID but no email, password or SAML message (`TracingTest`), `NoPiiInLogsTest` covers the rest |
| V16.3.1 | 2 | Done | T23 | Successful and failed logins with the factor type (`FlowApiTest`) |
| V16.3.2 | 2 | Partial | T28, T29 | Bus denials are audited (`BusDenialTest`), SAML denials remain |
| V16.3.3 | 2 | Partial | T28, T38 | Denials are audited and a throttle refusal reaches the log of security events (`ThrottleTest`, `LoginLimitsTest`), refused SAML messages remain |
| V16.3.4 | 2 | Planned | T38 | Unexpected errors and backend TLS failures are logged and alerted |
| V16.4.1 | 2 | Planned | T38 | The JSON encoder escapes log content |
| V16.4.2 | 2 | Partial | T41 | Audit is append-only (`AuditTest`), log storage remains |
| V16.4.3 | 2 | Planned | T38, T41 | Logs leave the services through the collector for a separate store |
| V16.5.1 | 2 | Done | T23 | `BusExceptionAdvice` answers refusals without a body, an unexpected error answers in general terms (`FailureResponsesTest`) |
| V16.5.2 | 2 | Done | T23 | Valkey, KMS and database failures are handled (`ThrottleTest`, `KeyHierarchyTest`, `FailureResponsesTest`) |
| V16.5.3 | 2 | Done | T23 | The bus denies on a missing rule (`BusDecoratorsTest`), a failed step proves nothing and opens no session (`FailureResponsesTest`) |

## V17 WebRTC

The application has no WebRTC.

| ID | L | Status | Tasks | Control |
|---|---|---|---|---|
| V17.1.1 | 2 | N/A | | No TURN service |
| V17.2.1 | 2 | N/A | | No media server |
| V17.2.2 | 2 | N/A | | No media server |
| V17.2.3 | 2 | N/A | | No media server |
| V17.2.4 | 2 | N/A | | No media server |
| V17.3.1 | 2 | N/A | | No signaling server |
| V17.3.2 | 2 | N/A | | No signaling server |
