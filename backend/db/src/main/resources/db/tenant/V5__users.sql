CREATE TABLE users (
    id               uuid PRIMARY KEY,
    email_index      bytea       NOT NULL UNIQUE,
    email_ciphertext bytea       NOT NULL,
    role             text        NOT NULL CHECK (role IN ('TENANT_ADMIN', 'USER')),
    status           text        NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'BLOCKED')),
    version          bigint      NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX users_active_admins ON users (id) WHERE role = 'TENANT_ADMIN' AND status = 'ACTIVE';

-- Deferred: the email is encrypted with the user's key, so the key row comes before the user row.
ALTER TABLE user_keys
    ADD CONSTRAINT user_keys_user_id_fkey FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE user_profiles (
    user_id           uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    name_ciphertext   bytea,
    phone_ciphertext  bytea,
    version           bigint NOT NULL DEFAULT 0
);

-- One row per user: a new invitation replaces the old one, and accepting deletes it.
CREATE TABLE invitations (
    user_id    uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    token_hash bytea       NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL
);
