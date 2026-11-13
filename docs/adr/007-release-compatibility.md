# ADR-007: Release compatibility

## Status

Accepted

## Decision

- **Old frontends:** a new backend serves the frontends of previous releases, and their files stay available after a release.
- **Expand and contract:** API contracts, events and migrations change in two steps. The new shape is added first, and the old one is removed in a later release, once nothing uses it.
- **No version in URLs:** the API is internal to our frontends, so it has one version at a time.
- **Client version:** every response carries `X-App-Version`. A frontend sends `X-Client-Version`, and `ClientVersionFilter` answers `426` when that version is below the configured minimum or unreadable. The minimum is raised by hand when a contract step removes something an old frontend needs.
- **Open pages:** a frontend is built with the version of its release and compares it with `X-App-Version`. On a difference it shows a banner and reloads on its next step, never in the middle of a form. A step it does not know and a chunk that fails to load reload it at once, and a `426` keeps the form and asks for a reload. A page reloads itself at most once in 30 seconds.
- **CI check:** `oasdiff` compares each contract in `contracts/openapi/` with the last release tag that the PR reaches, `release/production/<version>`, or `release/staging/<version>` while production has no release, and fails on a breaking change.

## Rationale

A browser keeps an old bundle open for hours, and a deploy cannot reload it. Without these rules every release would break forms that were open during it. Comparing with the last release, not with `main`, matches what old clients actually call, and production runs the oldest of them.
