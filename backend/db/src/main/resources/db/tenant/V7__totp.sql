-- last_used_step, failed_attempts and next_attempt_at stay outside version: they change on every login.
CREATE TABLE totp_credentials (
    user_id           uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    secret_ciphertext bytea   NOT NULL,
    confirmed_at      timestamptz,
    last_used_step    bigint,
    failed_attempts   integer NOT NULL DEFAULT 0,
    next_attempt_at   timestamptz,
    version           bigint  NOT NULL DEFAULT 0
);

CREATE TABLE recovery_codes (
    user_id   uuid  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash bytea NOT NULL,
    PRIMARY KEY (user_id, code_hash)
);
