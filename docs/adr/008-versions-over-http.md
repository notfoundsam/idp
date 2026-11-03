# ADR-008: Versions over HTTP

## Status

Accepted

## Decision

- **Where:** a resource that a client reads and replaces as a whole carries the version of its aggregate. The first is the tenant policy. A call that sets one value or moves a state, such as a role, a block or an MFA reset, carries none.
- **How:** `GET` answers the version in a strong `ETag`, such as `"7"`. A `PUT` sends it back in `If-Match`. Without the header the answer is `428`, and with a value that is not a version of ours, `*` and weak tags included, `412`. A version that is no longer current raises `VersionConflictException` in the service, and the controller answers `412` for it. A saved change answers its new `ETag`.
- **Order:** the header is read before the bus, like the body, so a call without it gets `428` before authorization runs.

## Rationale

A form shows a whole policy, and saving it writes every field. Without the version a second admin would undo a change they never saw, and the journal would only show it afterwards. `If-Match` is the HTTP way to state the version read, so it needs no field in the body, and `412` tells the client to read again rather than retry. Making the header required keeps a client that forgets it from saving blind.

A role, a block or a reset states the whole intent in the call itself: the service checks the current state, refuses a block of a blocked user and records the old role next to the new one. A version there would refuse a call that is still right, so those calls go without one.
