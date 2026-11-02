-- Nothing starts a flow before this release, so no row lacks a hash.
-- step_lease_until is outside version: it only tells a parallel step to give up early, version keeps the flow correct on its own.
ALTER TABLE flows
    ADD COLUMN browser_hash     bytea NOT NULL,
    ADD COLUMN step_lease_until timestamptz;
