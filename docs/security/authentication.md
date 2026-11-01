# Authentication

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
