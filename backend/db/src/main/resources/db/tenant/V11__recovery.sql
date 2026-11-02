-- One row per user: a new request replaces the old one, and using it deletes it.
CREATE TABLE password_resets (
    user_id    uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    token_hash bytea       NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL
);

CREATE INDEX password_resets_expires_at ON password_resets (expires_at);

-- token_hash stays empty until the mail handler issues the token, which then exists only in the mail.
CREATE TABLE email_changes (
    user_id              uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    new_email_ciphertext bytea       NOT NULL,
    token_hash           bytea UNIQUE,
    expires_at           timestamptz NOT NULL
);

CREATE INDEX email_changes_expires_at ON email_changes (expires_at);

-- The old address has to outlive the change for as long as it takes to tell its owner. The handler deletes the row after the mail.
CREATE TABLE email_change_notices (
    user_id              uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    old_email_ciphertext bytea       NOT NULL,
    expires_at           timestamptz NOT NULL
);

CREATE INDEX email_change_notices_expires_at ON email_change_notices (expires_at);
