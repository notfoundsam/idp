-- An accepted AuthnRequest waits here for the login it started. browser_hash stays empty until the browser comes back to its own
-- origin, because a cross-site arrival carries none of the cookies that bind a flow.
CREATE TABLE saml_requests (
    id                     uuid PRIMARY KEY,
    sp_id                  uuid        NOT NULL REFERENCES service_providers (id) ON DELETE CASCADE,
    acs_url                text        NOT NULL,
    request_ciphertext     bytea       NOT NULL,
    relay_state_ciphertext bytea,
    browser_hash           bytea,
    expires_at             timestamptz NOT NULL
);

CREATE INDEX saml_requests_expires_at ON saml_requests (expires_at);

-- A request ID is kept as a hash for as long as its IssueInstant would still be accepted, so a replay inside that window is refused.
CREATE TABLE saml_request_ids (
    sp_id      uuid        NOT NULL REFERENCES service_providers (id) ON DELETE CASCADE,
    id_hash    bytea       NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (sp_id, id_hash)
);

CREATE INDEX saml_request_ids_expires_at ON saml_request_ids (expires_at);
