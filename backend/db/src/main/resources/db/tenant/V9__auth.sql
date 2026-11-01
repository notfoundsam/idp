-- One row, inserted here, so a new tenant logs in under the defaults before anyone sets a policy.
CREATE TABLE tenant_policies (
    id                       boolean PRIMARY KEY DEFAULT true CHECK (id),
    mfa_for_users            boolean NOT NULL DEFAULT true,
    passkey_required         boolean NOT NULL DEFAULT false,
    session_idle_seconds     bigint  NOT NULL DEFAULT 1800 CHECK (session_idle_seconds > 0),
    session_absolute_seconds bigint  NOT NULL DEFAULT 43200,
    version                  bigint  NOT NULL DEFAULT 0,
    CHECK (session_absolute_seconds >= session_idle_seconds)
);

INSERT INTO tenant_policies DEFAULT VALUES;

-- last_seen_at stays outside version: it changes on requests, not by a decision.
CREATE TABLE sso_sessions (
    id                    uuid PRIMARY KEY,
    user_id               uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash            bytea       NOT NULL UNIQUE,
    factors               text[]      NOT NULL,
    ip_ciphertext         bytea,
    user_agent_ciphertext bytea,
    authenticated_at      timestamptz NOT NULL,
    last_seen_at          timestamptz NOT NULL,
    revoked_at            timestamptz,
    version               bigint      NOT NULL DEFAULT 0
);

CREATE INDEX sso_sessions_user_id ON sso_sessions (user_id);

-- A login that matched nobody leaves user_id empty, login_ciphertext is what tells it from a flow still waiting for one.
-- session_id has no foreign key: a session that is gone must refuse the flow built on it, not quietly detach from it.
CREATE TABLE flows (
    id                 uuid PRIMARY KEY,
    user_id            uuid REFERENCES users (id) ON DELETE CASCADE,
    login_ciphertext   bytea,
    proved             text[]      NOT NULL,
    required_assurance text        NOT NULL CHECK (required_assurance IN ('SINGLE_FACTOR', 'MULTI_FACTOR', 'PASSKEY')),
    fresh_login        boolean     NOT NULL,
    return_url         text,
    session_id         uuid,
    expires_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0
);

CREATE INDEX flows_user_id ON flows (user_id);
CREATE INDEX flows_expires_at ON flows (expires_at);
