-- `ops` stores a contact and cannot read it back. `worker` reads it once and deletes it.
CREATE TABLE admin_reinvites (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES tenants (id),
    contact_ciphertext bytea       NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now()
);

GRANT INSERT ON admin_reinvites TO ops_runtime;
GRANT SELECT, DELETE ON admin_reinvites TO worker_runtime;
