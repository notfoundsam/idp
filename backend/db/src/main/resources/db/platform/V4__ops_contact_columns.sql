-- `ops` stores a contact once and reads only its mask. Column grants hold this even for a query that asks for more.
REVOKE SELECT, UPDATE ON tenant_applications FROM ops_runtime;

GRANT SELECT (id, company_name, requested_subdomain, contact_mask, status, tenant_id, version, created_at)
    ON tenant_applications TO ops_runtime;
GRANT UPDATE (status, version) ON tenant_applications TO ops_runtime;
