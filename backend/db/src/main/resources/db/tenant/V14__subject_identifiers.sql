-- A persistent NameID: random, one per user and SP, so two SPs cannot join what they know of a user. It follows the user, not the
-- email, so a changed address keeps it and a new user under an old address gets another.
CREATE TABLE subject_identifiers (
    sp_id      uuid        NOT NULL REFERENCES service_providers (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    value      text        NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (sp_id, user_id),
    UNIQUE (sp_id, value)
);

CREATE INDEX subject_identifiers_user_id ON subject_identifiers (user_id);

-- Nothing is released until an admin names it for the SP.
ALTER TABLE service_providers
    ADD COLUMN released_attributes text[] NOT NULL DEFAULT '{}';

-- ForceAuthn needs a login after the request came in. Rows of the previous release live for minutes, so now() is near enough for them.
ALTER TABLE saml_requests
    ADD COLUMN accepted_at timestamptz NOT NULL DEFAULT now();
