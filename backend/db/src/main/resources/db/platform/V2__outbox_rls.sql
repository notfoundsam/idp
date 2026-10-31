ALTER TABLE outbox ENABLE ROW LEVEL SECURITY;

-- One policy for every tenant role: a tenant role name is derived from the tenant id, see TenantSchema.
CREATE POLICY outbox_tenant_insert ON outbox FOR INSERT TO PUBLIC
    WITH CHECK ('tenant_' || replace(tenant_id::text, '-', '') = current_user::text);

CREATE POLICY outbox_worker ON outbox FOR ALL TO worker_runtime
    USING (true)
    WITH CHECK (true);

CREATE POLICY outbox_ops_insert ON outbox FOR INSERT TO ops_runtime
    WITH CHECK (true);
