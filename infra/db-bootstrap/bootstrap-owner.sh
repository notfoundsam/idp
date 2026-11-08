#!/bin/sh
# Runs once on a new RDS instance as its master user, which is no superuser, and does what dev-tools/postgres/init.sql does
# locally. A repeat changes nothing but the passwords.
set -eu

: "${IDP_OWNER_PASSWORD:?}" "${PROVISIONER_PASSWORD:?}"

export PGSSLMODE="${PGSSLMODE:-verify-full}"
if [ -n "${DB_TLS_CA:-}" ]; then
  umask 077
  printf '%s\n' "$DB_TLS_CA" > "${TMPDIR:-/tmp}/db-ca.pem"
  export PGSSLROOTCERT="${TMPDIR:-/tmp}/db-ca.pem"
fi

psql -v ON_ERROR_STOP=1 -q <<'SQL'
\getenv owner_password IDP_OWNER_PASSWORD
\getenv provisioner_password PROVISIONER_PASSWORD
BEGIN;
SELECT 'CREATE ROLE idp_owner LOGIN CREATEROLE' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'idp_owner') \gexec
ALTER ROLE idp_owner PASSWORD :'owner_password';
-- `provisioner` joins idp_owner to run DDL, and a role cannot grant itself.
SELECT 'CREATE ROLE provisioner LOGIN NOINHERIT IN ROLE idp_owner'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'provisioner') \gexec
ALTER ROLE provisioner PASSWORD :'provisioner_password';
-- Handing the database over needs the right to become its new owner, which the master user holds only for this step.
GRANT idp_owner TO CURRENT_USER WITH INHERIT FALSE, SET TRUE;
SELECT format('ALTER DATABASE %I OWNER TO idp_owner', current_database()) \gexec
REVOKE idp_owner FROM CURRENT_USER;
COMMIT;
SQL
