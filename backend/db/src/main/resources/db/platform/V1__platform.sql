REVOKE ALL ON SCHEMA public FROM PUBLIC;

-- Roles belong to the cluster, so they outlive a recreated database.
DO $$
DECLARE
    runtime text;
BEGIN
    FOREACH runtime IN ARRAY ARRAY['app_runtime', 'worker_runtime', 'ops_runtime'] LOOP
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = runtime) THEN
            EXECUTE format('CREATE ROLE %I LOGIN NOINHERIT', runtime);
        END IF;
    END LOOP;
END
$$;

CREATE TABLE tenants (
    id                uuid PRIMARY KEY,
    name              text        NOT NULL,
    status            text        NOT NULL CHECK (status IN (
        'REQUESTED', 'APPROVED', 'PROVISIONING', 'ACTIVE', 'SUSPENDED', 'OFFBOARDING', 'DELETED'
    )),
    version           bigint      NOT NULL DEFAULT 0,
    provisioning_step text,
    kek_uri           text,
    offboarding_after timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tenant_domains (
    domain    text PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants (id)
);

CREATE INDEX tenant_domains_tenant_id ON tenant_domains (tenant_id);

CREATE TABLE tenant_applications (
    id                  uuid PRIMARY KEY,
    company_name        text        NOT NULL,
    requested_subdomain text        NOT NULL,
    contact_ciphertext  bytea,
    contact_mask        text,
    status              text        NOT NULL,
    tenant_id           uuid REFERENCES tenants (id),
    version             bigint      NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id                uuid PRIMARY KEY,
    tenant_id         uuid,
    aggregate_type    text        NOT NULL,
    aggregate_id      uuid        NOT NULL,
    aggregate_version bigint      NOT NULL,
    event_type        text        NOT NULL,
    payload           jsonb       NOT NULL DEFAULT '{}',
    correlation_id    uuid        NOT NULL,
    causation_id      uuid,
    status            text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'DEAD')),
    attempts          integer     NOT NULL DEFAULT 0,
    available_at      timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX outbox_pending ON outbox (available_at) WHERE status = 'PENDING';

CREATE TABLE operator_audit (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor       text        NOT NULL,
    action      text        NOT NULL,
    tenant_id   uuid,
    details     jsonb       NOT NULL DEFAULT '{}',
    occurred_at timestamptz NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA platform TO app_runtime, worker_runtime, ops_runtime;

GRANT SELECT ON tenants, tenant_domains TO app_runtime;

GRANT SELECT ON tenant_domains TO worker_runtime;
GRANT SELECT, UPDATE ON tenants, tenant_applications TO worker_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON outbox TO worker_runtime;

GRANT SELECT, INSERT, UPDATE ON tenants, tenant_domains, tenant_applications TO ops_runtime;
GRANT INSERT ON outbox TO ops_runtime;
GRANT SELECT, INSERT ON operator_audit TO ops_runtime;
