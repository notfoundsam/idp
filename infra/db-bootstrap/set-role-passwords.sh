#!/bin/sh
# Sets the password of every role named as an argument from <ROLE>_PASSWORD, so production feeds them from its secret store.
# Locally it runs as the superuser. In AWS it runs as idp_owner, which created the runtime roles and so may change them.
# \password hashes on the client and sends a SCRAM verifier, so no statement that Postgres logs or samples holds a password.
set -eu

[ "$#" -gt 0 ] || { echo "usage: set-role-passwords.sh ROLE..." >&2; exit 2; }

for role in "$@"; do
  case "$role" in
    '' | *[!a-z0-9_]*) echo "$role is no role name" >&2; exit 2 ;;
  esac
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

# psql reads the new password and its repetition from the lines after \password. printf is a builtin, so no process sees them.
for role in "$@"; do
  variable="$(echo "$role" | tr '[:lower:]' '[:upper:]')_PASSWORD"
  eval "value=\${$variable}"
  printf '\\password "%s"\n%s\n%s\n' "$role" "$value" "$value"
done | psql -v ON_ERROR_STOP=1 -q
