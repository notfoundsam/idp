-- One row: the EntityID is fixed at provisioning, so a later change of domains does not break the trust of every SP.
CREATE TABLE saml_identity (
    singleton  boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    entity_id  text NOT NULL,
    host       text NOT NULL
);

-- ACTIVE signs. NEXT is published in metadata first, so SPs learn it before it signs.
CREATE TABLE saml_signing_keys (
    id                     uuid PRIMARY KEY,
    status                 text        NOT NULL UNIQUE CHECK (status IN ('ACTIVE', 'NEXT')),
    certificate            bytea       NOT NULL,
    private_key_ciphertext bytea       NOT NULL,
    created_at             timestamptz NOT NULL
);

CREATE TABLE service_providers (
    id                     uuid PRIMARY KEY,
    entity_id              text    NOT NULL UNIQUE,
    name                   text    NOT NULL,
    signing_certificates   bytea[] NOT NULL,
    authn_requests_signed  boolean NOT NULL,
    name_id_format         text    NOT NULL CHECK (name_id_format IN ('EMAIL', 'PERSISTENT')),
    signed_part            text    NOT NULL CHECK (signed_part IN ('ASSERTION', 'RESPONSE', 'BOTH')),
    signature_algorithm    text    NOT NULL CHECK (signature_algorithm IN ('RSA_PSS_SHA256', 'RSA_SHA256')),
    version                bigint  NOT NULL DEFAULT 0,
    created_at             timestamptz NOT NULL
);

CREATE TABLE sp_acs_endpoints (
    sp_id      uuid    NOT NULL REFERENCES service_providers (id) ON DELETE CASCADE,
    acs_index  integer NOT NULL,
    url        text    NOT NULL,
    is_default boolean NOT NULL,
    PRIMARY KEY (sp_id, acs_index)
);

-- No version: an assignment is there or not, and a row per pair keeps two admins from conflicting over one SP.
CREATE TABLE sp_assignments (
    sp_id       uuid        NOT NULL REFERENCES service_providers (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    assigned_at timestamptz NOT NULL,
    PRIMARY KEY (sp_id, user_id)
);

CREATE INDEX sp_assignments_user_id ON sp_assignments (user_id);
