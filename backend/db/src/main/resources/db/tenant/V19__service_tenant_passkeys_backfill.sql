-- V18 locked the passkeys of the service tenant only where they were still required. An admin of a service tenant bootstrapped before
-- V17 may have turned them off, and its event does not come again. The SP ops that V18 flagged tells that worker set the tenant up.
UPDATE tenant_policies
SET passkey_required = true,
    passkeys_locked  = true,
    version          = version + 1
WHERE NOT (passkey_required AND passkeys_locked)
  AND EXISTS (SELECT 1
              FROM service_providers sp
                       JOIN platform.tenant_domains d
                            ON sp.entity_id = 'https://ops.' || substring(d.domain FROM 9) || '/saml2/metadata'
              WHERE 'tenant_' || replace(d.tenant_id::text, '-', '') = current_schema()
                AND d.domain LIKE 'ops-sso.%'
                AND sp.managed);
