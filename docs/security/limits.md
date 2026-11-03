# Limits

Every limit a client can reach. Limits in Postgres survive anything. Limits in Valkey stop being enforced when Valkey fails, and the failure raises an alert, so the Postgres limits are the ones that protect an account.

## Limits by caller

Valkey counts these in `app`, one bucket per message, and every key holds the tenant, so tenants share nothing. An address is stored as a keyed HMAC. A message checks the address first, so a blocked address spends nothing of its tenant.

| Messages | Per address | Per tenant |
|---|---|---|
| Starting a flow, entering a login, asking for a challenge or an enrollment | 30 a minute | 6,000 a minute |
| Password, TOTP, recovery code, passkey, confirming an enrollment, using a token from a mail | 10 a minute | 1,000 a minute |
| Asking for a password reset or an email change | 5 an hour | 500 an hour |
| In the account: the current password, confirming a TOTP or a passkey | 10 a minute, per user | None, only a session gets here |
| Any other message | 300 a minute, per user with a session, per address without | None |
| SAML SSO, two requests for each login through an SP | 30 a minute | 6,000 a minute |
| SAML metadata | 120 a minute | None, so nobody can keep the SPs of a tenant from it |

SAML does not pass the bus, so `SamlThrottleFilter` counts its requests on the same port, after the tenant and the session are known.

`THROTTLE_ENABLED=false` removes these for load tests, and `THROTTLE_MULTIPLIER` multiplies every capacity in this table and keeps its period. The local environment sets the multiplier to 100, because every local request comes from one address. `app` refuses to start in the `prod` profile with either setting changed, and a multiplier below 1 is refused everywhere. `ops` and the operator CLI have no such limits: VPN and IAM stand in front of them.

A mail is limited by the caller and not by the mailbox, because a limit per login would tell that the login exists. So many addresses can still send one person many reset mails, up to the tenant bucket, and each new mail cancels the link of the previous one. Nobody gets into the account this way.

## Limits by account

Postgres counts these, so they hold without Valkey and with `THROTTLE_ENABLED=false`.

| Subject | Limit |
|---|---|
| Password attempts | Three free failures, then a delay from 1 second doubling up to 15 minutes |
| TOTP attempts | The same delay, counted apart from passwords |
| Live sessions | 10 per user, the one seen longest ago is revoked |
| Checks of a credential | One at a time on a flow, a parallel request gets `409` without spending an attempt |

## Limits by tenant

| Subject | Limit | Beyond it |
|---|---|---|
| Open flows | 100,000 | `429` |
| Accepted SAML requests waiting for the browser | 100,000 | `429` |

A flow lives for 15 minutes and is one of the few rows an anonymous caller can add. The others are the challenge of a passkey, which needs a flow and expires in 5 minutes, and the event and the audit row of a reset request, which only the limits by caller bound. The tenant limit on starting flows lets in 90,000 in that time, so with Valkey up this limit cannot be reached, and nobody can shut logins out by filling it. It exists for the time Valkey is down, when it bounds the rows. Then a botnet can fill it, and every login of that tenant waits until flows expire. Other tenants do not notice, since each counts its own schema.

## Limits on a request

| Subject | Limit | Beyond it |
|---|---|---|
| Body of an API or SAML request | 32 KiB, the same on every tenant | `413` |
| Body without a length | Refused, a browser always sends one | `411` |
| Client version | At least `MIN_CLIENT_VERSION`, when set | `426` |
| AuthnRequest, decoded and inflated | 32 KiB, counted while inflating | Error page |
| RelayState | 1 KiB | Error page |
| Age of an AuthnRequest | 3 minutes after IssueInstant, with 3 minutes of skew either way | SAML error at the ACS |

The largest legitimate body is a passkey attestation of a few KiB, and an AuthnRequest is about as large. SAML bindings ask for a RelayState of 80 bytes at most, which SPs that carry a return URL exceed.

An accepted AuthnRequest waits 15 minutes for its browser, like a flow, and is one more row an anonymous caller can add: an unsigned request needs only the EntityID of an SP. Its ID stays for as long as its IssueInstant would be accepted, so a replay is refused within that time and an old copy is refused for its age.

## Expensive operations

| Operation | Cost | Protection |
|---|---|---|
| Argon2id hash | About 40 ms of a CPU and 19 MiB | `HashLimit` runs one hash per vCPU with a queue of two and a wait of one second, then answers `429`. The permit comes before the attempt is counted, so overload does not count as a guess. No connection is held meanwhile |
| Flow start | One row and a key derivation | The limits above |
| Passkey signature | Milliseconds | A challenge is spent before the signature is checked |
| KMS call | A network round trip | Keys stay in a cache for 5 minutes, and a failure answers `500` without proceeding |

## Logging

A refusal by a limit reaches the log of security events as `event=throttle.rejected` with the message, the dimension and the tenant, and never the address. It does not reach the audit journal: a row per refusal would turn the flood it stops into writes on Postgres. A refused AuthnRequest goes the same way as `event=saml.request_rejected` with the reason and the tenant, and nothing of the request.
