#!/bin/sh
# Passwords come from the environment, so production can feed them from its secret store.
set -eu

for role in app_runtime worker_runtime ops_runtime provisioner; do
  variable="$(echo "$role" | tr '[:lower:]' '[:upper:]')_PASSWORD"
  eval "password=\${$variable}"
  psql -v ON_ERROR_STOP=1 -q -v role="$role" -v password="$password" <<'SQL'
ALTER ROLE :"role" PASSWORD :'password';
SQL
done
