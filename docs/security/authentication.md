# Authentication

## Login steps

`AuthFlow` derives the next step from what was proved, the tenant policy, the factors of the user and the demands of the caller. A client cannot skip a step, because any other step is refused. A flow lives for a fixed time, and no step extends it.

The first step takes a login or a passkey. A login that matches nobody leads to the same password step as any other, and a user without a password gets it too, so the steps say nothing about an account. That step still takes a passkey, but only the passkey of the user who entered the login. A passkey fails the same way when the login belongs to someone else and when it belongs to nobody, so a valid passkey of one's own cannot probe logins.

MFA is required by default, and a tenant can relax it only for the `USER` role. The policy has no switch for `TENANT_ADMIN`. A passkey with user verification is enough on its own. A second factor the user enrolled is asked for even under a relaxed policy.

A user with no second factor enrolls one right after the password when MFA is required, so until then the password alone stands behind that enrollment. A user who already has a factor must pass it first, so a stolen password cannot add a new one. When a passkey is required, neither TOTP nor a recovery code replaces it.

A flow can start from a session and then asks only for what is missing. A demand for a fresh login makes the session count for nothing. The role, the factors and the policy are read again at every step, so a tighter policy or a new factor reaches a login in progress. See [sessions.md](sessions.md) for what a completed flow opens.

## Ways in

Every session starts from a completed `AuthFlow`, and no other code opens one. The operator CLI opens none at all.

| Way in | What it proves | Strength |
|---|---|---|
| Password and TOTP | Something known, something held | Multi-factor |
| Password and recovery code | Something known, a code saved once | Multi-factor, weaker in practice because a saved code can be copied |
| Passkey with user verification | A device and a local check | Multi-factor, phishing-resistant |
| Password alone | Something known | Single factor, only where a tenant relaxed MFA for the `USER` role |
| Password, then enrolling a factor | Something known, then a new factor | Multi-factor from then on. Until then the password alone stands behind the enrollment |
| Invitation and a new password | Access to the mailbox, then a password of one's choice | Single factor. The flow goes on as after a password, so MFA follows unless a tenant relaxed it for the `USER` role |
| Password reset | Access to the mailbox | Replaces the password and opens no session. Other factors still apply |
| Operator CLI | IAM permission to start the one-off container | Outside the tenant boundary, sees no customer data, leaves `platform.operator_audit` |

## Login API

A flow lives in the URL and is bound to its browser by the `__Host-flow` cookie. Only the SHA-256 of the secret in that cookie is stored, as part of the key the flow is looked up by, so another browser finds no flow at all and reads the same as for an expired one. Tabs of one browser share the secret and keep separate flows.

A wrong credential, a running delay, an unknown login, a missing password, a blocked user and an invited user all give the same `422` with no body. An unknown login walks the same steps, costs the same database reads and one Argon2id hash, because `PasswordService.verify` hashes in every case. The status of the user is checked after the hash, so it does not show in the timing. Each refusal goes to the audit journal as `login.failed` with the factor, and with the user as the target when one is known. The entered login is not recorded, since people type passwords into that field.

A check reserves the flow first, with a lease in Postgres. A parallel request gets `409` before it spends an attempt or a one-time code, so a double click neither burns a code nor reports a wrong password. The lease ends by itself after 30 seconds if the check dies. It only decides who gives up: the version of the flow keeps the result correct without it.

Enrolling during a login is offered only at the step that asks for it, and proving a factor never allows adding one. Enrollment hands out recovery codes, because a user who enrolls during a login may have no other way back.

## Recovery

Links in mail carry a token of 256 bits from `SecureRandom`. Only its SHA-256 is stored, it works once, and a new one replaces the old. An invitation lives for 72 hours, a password reset and an email change for one hour. The handler in `worker` issues the token, so it exists only in the mail. It stands in the URL fragment, which browsers keep out of requests, logs and `Referer`, and the page sends it in a request body. Any refused token goes to the audit journal as `recovery.token_refused` with its kind.

An invitation is accepted on a flow, with the token and the first password in one call. The password is checked and hashed first, with no transaction open, and only then is the token spent, so a refused password keeps the invitation. A flow that took over a session does not take an invitation, so a browser with a live session starts one with `reauthenticate`.

Asking for a password reset answers the same in every case. A login that matches nobody costs the same index lookup, one event and one audit row, and the event finds no user and ends there. Only an active user gets a mail, and it goes to the stored address, because the blind index folds spelling and another spelling of an address is not that mailbox.

A reset spends the token, stores the password and revokes every session in one transaction. It also ends the logins in progress, so a password proved before the reset cannot open a session after it, and it cancels a waiting email change, because the session that asked for it may be the stolen one. It opens no session and clears no factor, so the next login asks for everything the account has.

Changing the email needs a session whose full login is at most 10 minutes old. A flow that only continues a session does not count, so a client walks one with `reauthenticate`. The request stays sealed in `email_changes` until the new address confirms it, and an address that belongs to someone else is refused only there, so a request cannot probe who has an account. Confirming moves the address and its blind index and cancels a reset whose link went to the old address. The old address is kept in `email_change_notices` until the notice has gone out, for 7 days at most. Of two quick changes the first address gets the notice, because its owner is the one who may not have asked.

A user who lost every factor has no way back alone: no recovery route removes or adds a factor. `IdentityService.resetMfa` removes TOTP, recovery codes and passkeys, and the tenant admin calls it after confirming who asks. An only admin who lost everything goes through the operator, see the [runbook](tenant-admin-recovery-runbook.md).

## Limits and lockout

An attacker who only wants to lock a user out can keep the delay of an account running by guessing once in every window. The delay stops at 15 minutes, so the owner can still log in between guesses, and it applies to passwords and TOTP but not to passkeys or recovery codes. Only the limits by address and by tenant stand against this, see [ADR-003](../adr/003-password-hashing.md). No account is ever locked outright, because a lock would hand this attack a guaranteed result.

An attacker who wants to shut a whole tenant out needs its bucket of 1,000 attempts a minute. That is a hundred addresses at the full rate, so the bucket is far above what a single address can spend, and each refused address spends nothing of it. [limits.md](limits.md) lists every limit.

## Context words

A password may not contain these words, compared in lowercase. Words under three characters are ignored.

- the product name, which is `idp` until the product has a name of its own
- the subdomain and the name of the tenant
- the part of the email before `@`

## Passkeys

Passkeys follow WebAuthn through WebAuthn4J. They are discoverable, so a login starts before anyone is known and no response tells whether an account exists.

The relying party ID is the full host of the tenant, never the shared parent domain, and the origin is that host over HTTPS. A passkey of one tenant therefore fails on another twice: its credential is not in that tenant's schema, and its signature covers a different RP ID. Changing the host of a tenant breaks its passkeys.

Registration and login both require user verification. A passkey that passed it counts as multi-factor. Attestation is not requested, so no decision depends on the make of an authenticator.

Only ES256, EdDSA and RS256 are accepted. RS256 is a recorded exception to V11.6.1, because some platform authenticators offer nothing else. The application only verifies it and never creates such a signature.

Every challenge is 256 bits from `SecureRandom`, lives in Postgres for 5 minutes and works once. It is spent by a single `DELETE` before the response is verified, so a failed attempt also uses it up. A registration challenge belongs to its user and cannot finish a login, and the reverse.

A login challenge is created before anyone is known, so an anonymous caller can add rows. They expire and a job removes them, and the limit on unfinished flows bounds them meanwhile.

A signature counter must grow, except that an authenticator reporting zero keeps none. A counter that fails to grow means two copies of the key may exist: the login is refused and the denial goes to the audit log. The credential stays active, because the owner cannot be told from the copy. Synced passkeys usually report zero, so this catches copies only of hardware keys.

## TOTP

TOTP follows RFC 6238 with a 30-second step, six digits and HMAC-SHA-1. Authenticator apps implement this function, so it stays as a recorded exception to V11.4.1. Nothing else may use SHA-1.

The secret is 160 bits from `SecureRandom`, encrypted with the user's key and bound to the row and field. A new secret counts for nothing until a code from it is confirmed, and a confirmed secret cannot be replaced by a new enrollment.

The server accepts the current step and one step on either side. That is at most 30 seconds of clock drift in each direction, and it never accepts more than three codes at once. One step covers a phone that is slightly off and a code typed at the end of its step.

A step works once. The last used step is stored, and a conditional `UPDATE` refuses any step that is not newer, so a parallel request with the same code cannot pass. This also refuses an older code that is still inside the tolerance after a newer one was used.

Every attempt is counted before the code is checked, by a single `UPDATE` that loses when another attempt got there first. The delay is the same as for passwords: three free failures, then one second doubling up to 15 minutes. The counters survive a restart and do not depend on the throttle.

## Recovery codes

A user holds ten recovery codes. Each carries 128 bits from `SecureRandom`, and only its SHA-256 hash is stored. That entropy needs neither a salt nor a slow hash, because nothing can be guessed offline. New codes replace the old ones.

A code works once: using it deletes its row in a single statement, so of two parallel uses only one finds it. Using a code does not count attempts, because 128 bits cannot be guessed online and a running TOTP delay must not close the way back in.
