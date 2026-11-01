-- failed_attempts and next_attempt_at stay outside version: a failed attempt must not conflict with a change of the password.
CREATE TABLE password_credentials (
    user_id         uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    hash            text    NOT NULL,
    failed_attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    version         bigint  NOT NULL DEFAULT 0
);
