CREATE TABLE tenant_keys (
    version     integer PRIMARY KEY,
    wrapped_key bytea       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_keys (
    user_id    uuid PRIMARY KEY,
    keyset     bytea       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
