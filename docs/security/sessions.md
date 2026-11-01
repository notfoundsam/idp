# Sessions

A session starts when a login flow completes. It belongs to one user of one tenant and lives in that tenant's schema, so a token from another tenant finds nothing.

## Timeouts

Both timeouts come from the tenant policy and are read on every request, so tightening them reaches sessions that are already open.

| Timeout | Default | Ends the session |
|---|---|---|
| Idle | 30 minutes | After this long without a request |
| Absolute | 12 hours | This long after the login, whatever the activity |

[NIST SP 800-63B-4](https://pages.nist.gov/800-63-4/sp800-63b.html) sets the reference points. AAL2 asks for an overall timeout of no more than 24 hours and an inactivity timeout of no more than 1 hour. AAL3 requires an overall timeout of no more than 12 hours and asks for an inactivity timeout of no more than 15 minutes.

The default login is multi-factor, so AAL2 is the bar. Both defaults stay within it. The absolute timeout also meets AAL3 and covers a working day, so people log in about once a day. The idle timeout is half the AAL2 limit, because a workforce IdP is often left open on a shared desk. It is deliberately above the 15 minutes of AAL3, which would interrupt anyone reading a long page in an SP.

After the absolute timeout the user logs in with all required factors. After the idle timeout the same happens: no shortcut with a single factor exists.

The last seen time is written at most once a minute, so a busy session does not write on every request. The idle timeout can therefore end up to a minute early, and never late.

A flow that continues a session keeps its login time. Otherwise repeating flows would push the absolute timeout away for as long as the session stays in use.

## Tokens

A token is 256 bits from `SecureRandom`. Only its SHA-256 is stored, so a stolen database opens no session.

The token travels only in the `__Host-session` cookie, marked `Secure`, `HttpOnly` and `SameSite=Lax`. The prefix keeps the cookie on its exact host, so one tenant cannot set it for a neighbour. The cookie has no `Max-Age`: the server decides when a session ends.

A completed login issues a new token and a new session ID, and the previous token of that browser stops working. That covers a fresh login and a step-up that proves a new factor. A flow that proves nothing new leaves the session as it is, so two SPs opening in parallel tabs do not invalidate each other.

## Concurrent sessions

A user holds at most 10 live sessions, roughly one per browser and device. A login beyond that revokes the session seen longest ago and notes this in the `session.opened` audit record. The new login always succeeds, so nobody can lock a user out by filling the limit.

## Revocation

Revoking joins the caller's transaction: a block or a password reset that rolls back leaves the sessions alone, and one that commits ends them together with the change. A revoked row stays until the hourly cleanup removes it.

The role and the status of the user are read on every request. A lower role applies from the next request, and a blocked user gets no further even before the sessions are revoked.

A suspended tenant is refused by its status before any session is read. The suspension event then revokes all its sessions, so they do not return when the tenant resumes.

A flow that took over a session fails once that session is revoked or timed out, so a dead session cannot pass its factors on.

## The IdP session and SP sessions

The IdP session is the only session this application holds for a user. Each SP keeps its own, created from the SAML Response and governed by that SP.

- Activity in an SP does not reach the IdP, so it extends neither IdP timeout.
- A new SP login while the IdP session is live needs no login, unless the SP asks for a fresh one or more assurance than the session has. The SP assignment is checked for every Response.
- Ending the IdP session does not end SP sessions, because Single Logout is outside the first version. The user has to log out of each SP, and an SP session runs until the SP ends it.
- An SP sees an ended IdP session only at its next redirect to the IdP, which then asks for a login.
