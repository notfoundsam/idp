-- V17 flags the SP ops and the passkeys of the service tenant only when worker sets them up, and its event does not come again, so a
-- service tenant bootstrapped before V17 would keep them open to its admins. Only the service tenant answers at ops-sso.<base domain>,
-- a reserved subdomain, and its SP ops has the EntityID https://ops.<base domain>/saml2/metadata.
UPDATE service_providers sp
SET managed = true
FROM platform.tenant_domains d
WHERE 'tenant_' || replace(d.tenant_id::text, '-', '') = current_schema()
  AND d.domain LIKE 'ops-sso.%'
  AND sp.entity_id = 'https://ops.' || substring(d.domain FROM 9) || '/saml2/metadata';

UPDATE tenant_policies
SET passkeys_locked = true
WHERE passkey_required
  AND EXISTS (SELECT 1
              FROM platform.tenant_domains d
              WHERE 'tenant_' || replace(d.tenant_id::text, '-', '') = current_schema()
                AND d.domain LIKE 'ops-sso.%');
