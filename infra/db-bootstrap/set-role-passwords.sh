#!/bin/sh
# Sets the password of every role named as an argument from <ROLE>_PASSWORD, so production feeds them from its secret store.
# Locally it runs as the superuser. In AWS it runs as idp_owner, which created the runtime roles and so may change them.
set -eu

[ "$#" -gt 0 ] || { echo "usage: set-role-passwords.sh ROLE..." >&2; exit 2; }

for role in "$@"; do
  variable="$(echo "$role" | tr '[:lower:]' '[:upper:]')_PASSWORD"
  eval "value=\${$variable:-}"
  [ -n "$value" ] || { echo "$variable is empty" >&2; exit 1; }
done
unset value

export PGSSLMODE="${PGSSLMODE:-verify-full}"
if [ -n "${DB_TLS_CA:-}" ]; then
  umask 077
  printf '%s\n' "$DB_TLS_CA" > "${TMPDIR:-/tmp}/db-ca.pem"
  export PGSSLROOTCERT="${TMPDIR:-/tmp}/db-ca.pem"
fi

for role in "$@"; do
  variable="$(echo "$role" | tr '[:lower:]' '[:upper:]')_PASSWORD"
  psql -v ON_ERROR_STOP=1 -q -v role="$role" -v variable="$variable" <<'SQL'
\getenv password :variable
ALTER ROLE :"role" PASSWORD :'password';
SQL
done
