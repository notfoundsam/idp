# Authentication

## Login steps

`AuthFlow` derives the next step from what was proved, the tenant policy, the factors of the user and the demands of the caller. A client cannot skip a step, because any other step is refused. A flow lives for a fixed time, and no step extends it.

The first step takes a login or a passkey. A login that matches nobody leads to the same password step as any other, and a user without a password gets it too, so the steps say nothing about an account. That step still takes a passkey, but only the passkey of the user who entered the login. A passkey fails the same way when the login belongs to someone else and when it belongs to nobody, so a valid passkey of one's own cannot probe logins.

MFA is required by default, and a tenant can relax it only for the `USER` role. The policy has no switch for `TENANT_ADMIN`. A passkey with user verification is enough on its own. A second factor the user enrolled is asked for even under a relaxed policy.

A user with no second factor enrolls one right after the password when MFA is required, so until then the password alone stands behind that enrollment. A user who already has a factor must pass it first, so a stolen password cannot add a new one. When a passkey is required, neither TOTP nor a recovery code replaces it.

A flow can start from a session and then asks only for what is missing. A demand for a fresh login makes the session count for nothing.

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
