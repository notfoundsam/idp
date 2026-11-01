-- No version: nothing here changes by a user's decision, and sign_count, backed_up and last_used_at change on every login.
CREATE TABLE passkey_credentials (
    id                       uuid PRIMARY KEY,
    user_id                  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    credential_id            bytea       NOT NULL UNIQUE,
    attested_credential_data bytea       NOT NULL,
    sign_count               bigint      NOT NULL,
    backup_eligible          boolean     NOT NULL,
    backed_up                boolean     NOT NULL,
    created_at               timestamptz NOT NULL,
    last_used_at             timestamptz
);

CREATE INDEX passkey_credentials_user_id ON passkey_credentials (user_id);

-- A row with a user is a registration, without one a login: the login starts before anyone is known.
CREATE TABLE passkey_challenges (
    challenge  bytea PRIMARY KEY,
    user_id    uuid REFERENCES users (id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);

CREATE INDEX passkey_challenges_expires_at ON passkey_challenges (expires_at);
