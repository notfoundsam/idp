# ADR-007: Release compatibility

## Status

Accepted

## Decision

- **Old frontends:** a new backend serves the frontends of previous releases, and their files stay available after a release.
- **Expand and contract:** API contracts, events and migrations change in two steps. The new shape is added first, and the old one is removed in a later release, once nothing uses it.
- **No version in URLs:** the API is internal to our frontends, so it has one version at a time.
- **Client version:** every response carries `X-App-Version`. A frontend sends `X-Client-Version`, and `ClientVersionFilter` answers `426` when that version is below the configured minimum or unreadable. The minimum is raised by hand when a contract step removes something an old frontend needs.
- **CI check:** `oasdiff` compares each contract in `contracts/openapi/` with the last release tag and fails on a breaking change.

## Rationale

A browser keeps an old bundle open for hours, and a deploy cannot reload it. Without these rules every release would break forms that were open during it. Comparing with the last release, not with `main`, matches what old clients actually call.
