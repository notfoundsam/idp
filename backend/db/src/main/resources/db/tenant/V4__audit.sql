CREATE TABLE audit_entries (
    id                 uuid PRIMARY KEY,
    occurred_at        timestamptz NOT NULL,
    action             text        NOT NULL,
    actor_type         text        NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM', 'ANONYMOUS')),
    actor_id           uuid,
    target_type        text,
    target_id          uuid,
    ip_ciphertext      bytea,
    details_ciphertext bytea,
    CHECK ((actor_type = 'USER') = (actor_id IS NOT NULL)),
    CHECK ((target_type IS NULL) = (target_id IS NULL))
);

CREATE INDEX audit_entries_journal ON audit_entries (occurred_at DESC, id DESC);

-- The default privileges of the baseline give every new table full DML, and this one is append-only.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_entries FROM ${flyway:defaultSchema};
