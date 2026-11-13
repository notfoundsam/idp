#!/bin/sh
# Runs once on a new RDS instance as its master user, which is no superuser, and does what dev-tools/postgres/init.sql does
# locally. A repeat changes nothing but the passwords. \password hashes on the client and sends a SCRAM verifier, so no
# statement that Postgres logs or samples holds a password.
set -eu

: "${IDP_OWNER_PASSWORD:?}" "${PROVISIONER_PASSWORD:?}"

export PGSSLMODE="${PGSSLMODE:-verify-full}"
if [ -n "${DB_TLS_CA:-}" ]; then
  umask 077
  printf '%s\n' "$DB_TLS_CA" > "${TMPDIR:-/tmp}/db-ca.pem"
  export PGSSLROOTCERT="${TMPDIR:-/tmp}/db-ca.pem"
fi

# psql reads the new password and its repetition from the lines after \password. printf is a builtin, so no process sees them.
{
  cat <<'SQL'
BEGIN;
SELECT 'CREATE ROLE idp_owner LOGIN CREATEROLE' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'idp_owner') \gexec
\password idp_owner
SQL
  printf '%s\n%s\n' "$IDP_OWNER_PASSWORD" "$IDP_OWNER_PASSWORD"
  cat <<'SQL'
-- `provisioner` joins idp_owner to run DDL, and a role cannot grant itself.
SELECT 'CREATE ROLE provisioner LOGIN NOINHERIT IN ROLE idp_owner'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'provisioner') \gexec
\password provisioner
SQL
  printf '%s\n%s\n' "$PROVISIONER_PASSWORD" "$PROVISIONER_PASSWORD"
  cat <<'SQL'
-- Handing the database over needs the right to become its new owner, which the master user holds only for this step.
GRANT idp_owner TO CURRENT_USER WITH INHERIT FALSE, SET TRUE;
SELECT format('ALTER DATABASE %I OWNER TO idp_owner', current_database()) \gexec
REVOKE idp_owner FROM CURRENT_USER;
COMMIT;
SQL
} | psql -v ON_ERROR_STOP=1 -q
