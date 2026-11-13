-- The service tenant is the IdP of ops, and worker sets up its SP and its passkey policy. A tenant admin who removed or reconfigured
-- that SP, or let a login without a passkey through, would lock every operator out of ops, so only the system may change them.
ALTER TABLE service_providers ADD COLUMN managed boolean NOT NULL DEFAULT false;

ALTER TABLE tenant_policies ADD COLUMN passkeys_locked boolean NOT NULL DEFAULT false;
