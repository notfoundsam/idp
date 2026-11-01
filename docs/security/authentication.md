# Authentication

## TOTP

TOTP follows RFC 6238 with a 30-second step, six digits and HMAC-SHA-1. Authenticator apps implement this function, so it stays as a recorded exception to V11.4.1. Nothing else may use SHA-1.

The secret is 160 bits from `SecureRandom`, encrypted with the user's key and bound to the row and field. A new secret counts for nothing until a code from it is confirmed, and a confirmed secret cannot be replaced by a new enrollment.

The server accepts the current step and one step on either side. That is at most 30 seconds of clock drift in each direction, and it never accepts more than three codes at once. One step covers a phone that is slightly off and a code typed at the end of its step.

A step works once. The last used step is stored, and a conditional `UPDATE` refuses any step that is not newer, so a parallel request with the same code cannot pass. This also refuses an older code that is still inside the tolerance after a newer one was used.

Every attempt is counted before the code is checked, by a single `UPDATE` that loses when another attempt got there first. The delay is the same as for passwords: three free failures, then one second doubling up to 15 minutes. The counters survive a restart and do not depend on the throttle.

## Recovery codes

A user holds ten recovery codes. Each carries 128 bits from `SecureRandom`, and only its SHA-256 hash is stored. That entropy needs neither a salt nor a slow hash, because nothing can be guessed offline. New codes replace the old ones.

A code works once: using it deletes its row in a single statement, so of two parallel uses only one finds it. Using a code does not count attempts, because 128 bits cannot be guessed online and a running TOTP delay must not close the way back in.
