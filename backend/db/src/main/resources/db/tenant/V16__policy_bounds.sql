-- The bounds TenantPolicy checks on every load: idle 5 to 60 minutes, absolute from idle up to 24 hours. A row outside them would fail
-- every session check and login of its tenant, so it moves to the nearest bound first. The version moves too, so an admin who read
-- the old values cannot save over the change without seeing it.
UPDATE tenant_policies
SET session_idle_seconds     = LEAST(GREATEST(session_idle_seconds, 300), 3600),
    session_absolute_seconds = LEAST(GREATEST(session_absolute_seconds, LEAST(GREATEST(session_idle_seconds, 300), 3600)), 86400),
    version                  = version + 1
WHERE session_idle_seconds NOT BETWEEN 300 AND 3600
   OR session_absolute_seconds NOT BETWEEN session_idle_seconds AND 86400;

ALTER TABLE tenant_policies
    DROP CONSTRAINT tenant_policies_session_idle_seconds_check,
    DROP CONSTRAINT tenant_policies_check,
    ADD CONSTRAINT session_idle_in_bounds CHECK (session_idle_seconds BETWEEN 300 AND 3600),
    ADD CONSTRAINT session_absolute_in_bounds CHECK (session_absolute_seconds BETWEEN session_idle_seconds AND 86400);
