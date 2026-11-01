# ADR-003: Password hashing and attempts

## Status

Accepted

## Decision

Passwords are stored as Argon2id hashes in `password_credentials`, one row per user, and hashing happens outside any transaction.

- **Parameters:** Argon2id with `m=19 MiB, t=2, p=1`, a 16-byte salt and a 32-byte hash, computed by Bouncy Castle. The parameters stay in the hash string in PHC format.
- **Upgrade:** verification reads the parameters from the string. When they differ from the current ones, a successful login recomputes the hash. The write is conditional on `version`, so it loses to a password changed in the meantime.
- **Concurrency:** `HashLimit` lets as many hashes run as there are vCPUs, with a queue of two per vCPU and a wait of one second. A request past the queue gets `429` before it allocates hash memory. The limit also covers the attempt claim, so overload never counts as a guess.
- **Attempts:** an attempt is claimed by a conditional `UPDATE` before the hash and cleared on success. The first three failures are free, then the delay doubles from one second up to 15 minutes. `failed_attempts` and `next_attempt_at` sit outside `version`, and a failure leaves the `User` aggregate alone.
- **Uniform answer:** `PasswordService.verify` returns `false` for a wrong password, a running delay, a user without a password and an unknown login, each at the cost of one hash.
- **Rules:** 8 to 128 characters counted as code points, and the interface recommends 15. There are no composition or expiry rules. The password is hashed exactly as received, without trimming, case folding or Unicode normalization.
- **Refused passwords:** a password is compared in lowercase against a list in the build, and it may not contain a context word: the product name, the subdomain and name of the tenant, or the part of the email before `@`. Words under three characters are ignored. Nothing calls an outside service.
- **The list:** `banned-passwords.txt` merges the NCSC top 100k, taken from Have I Been Pwned, and the Xato top 100k, both from [SecLists](https://github.com/danielmiessler/SecLists) under the MIT license. It keeps the printable ASCII entries that the length rule would accept, 65 thousand in all.

### Measurements

Corretto `25.0.5-al2023-headless` with default JVM flags on an Apple M2, so production numbers will differ. Each row ran 600 hashes with `threads` at once, and the two-CPU rows are the middle of three runs. Time alone is one hash with nothing else running.

| `--cpus` | `--memory` | Max heap | threads | Time alone, p50 | Time alone, p95 | Hashes per second | GC time |
|---|---|---|---|---|---|---|---|
| 0.5 | 512m | 123 MiB | 1 | 88 ms | 164 ms | 11 | 14.5 s |
| 1 | 512m | 123 MiB | 1 | 38 ms | 57 ms | 24 | 6.7 s |
| 2 | 1g | 247 MiB | 2 | 31 ms | 41 ms | 40 | 5.1 s |
| 2 | 1g | 247 MiB | 4 | 31 ms | 40 ms | 44 | 6.4 s |
| 2 | 1g | 247 MiB | 8 | 31 ms | 40 ms | 26 | 16.4 s |

Each hash allocates 19 MiB on the heap, in `PasswordHasherTest`. No run hit an `OutOfMemoryError`.

## Rationale

Argon2id at these parameters is the OWASP recommendation that ASVS Appendix C accepts. Bouncy Castle is pure Java, so the same artifact runs on every platform without a native library, and its output matches the reference CLI in `PasswordHasherTest`.

Hashing holds memory and a CPU for tens of milliseconds, which a pooled PgBouncer connection must not wait for. Past the vCPU count more threads gain little and then lose: four threads on two CPUs add about 10%, while eight lose about a third and triple the GC time, with 152 MiB of a 247 MiB heap held by hashes. One permit per vCPU keeps the heap bound simple and stays clear of that drop, so rejecting early keeps the service responsive under a flood.

Claiming before hashing means a crash or a parallel burst cannot win free guesses, since a request that never reports back has already paid. Holding no lock during the claim keeps a hot account from stalling other work, and the loser of a race is refused like any other wrong guess. A login form submits once, so a legitimate user does not meet that race.

A delay instead of a lockout means an attacker cannot lock a victim out for good by guessing. The 15-minute cap still limits guessing to about 100 tries per account per day. An attacker can keep one account delayed by guessing at each window, and only the IP and tenant limits in front of login reduce that.

A uniform answer stops login from revealing which accounts exist, at the price that a user with the right password during a delay sees a plain failure. The screen has to say that the password is wrong or that there were too many attempts.

The length limit exists because Argon2id accepts any input, and it is checked first so a huge password costs nothing. 128 characters leave room for passphrases and password managers. Identity cannot reach the tenancy context, so the audience passes the tenant's words in, and identity adds the rest.
