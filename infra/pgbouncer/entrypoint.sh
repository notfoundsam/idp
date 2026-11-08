#!/bin/sh
# ECS hands secrets over only as variables, so the files PgBouncer reads are written here from the environment.
set -eu

: "${DB_HOST:?}" "${DB_NAME:?}" "${CLIENT_TLS_CERT:?}" "${CLIENT_TLS_KEY:?}" "${SERVER_TLS_CA:?}"

dir=/run/idp
umask 077

printf '%s\n' "$CLIENT_TLS_CERT" > "$dir/client.crt"
printf '%s\n' "$CLIENT_TLS_KEY" > "$dir/client.key"
printf '%s\n' "$SERVER_TLS_CA" > "$dir/server-ca.pem"

: > "$dir/userlist.txt"
for role in app_runtime worker_runtime ops_runtime; do
  variable="$(echo "$role" | tr '[:lower:]' '[:upper:]')_PASSWORD"
  eval "password=\${$variable:?}"
  printf '"%s" "%s"\n' "$role" "$(printf '%s' "$password" | sed 's/"/""/g')" >> "$dir/userlist.txt"
  unset "$variable"
done
unset password CLIENT_TLS_KEY

cat > "$dir/pgbouncer.ini" <<EOF
[databases]
$DB_NAME = host=$DB_HOST port=${DB_PORT:-5432} dbname=$DB_NAME

[pgbouncer]
listen_addr = 0.0.0.0
listen_port = 5432
unix_socket_dir =
auth_type = scram-sha-256
auth_file = $dir/userlist.txt
pool_mode = transaction
max_client_conn = ${MAX_CLIENT_CONN:-1000}
default_pool_size = ${DEFAULT_POOL_SIZE:-20}
ignore_startup_parameters = extra_float_digits
client_tls_sslmode = require
client_tls_cert_file = $dir/client.crt
client_tls_key_file = $dir/client.key
client_tls_protocols = tlsv1.2,tlsv1.3
server_tls_sslmode = verify-full
server_tls_ca_file = $dir/server-ca.pem
server_tls_protocols = tlsv1.2,tlsv1.3
EOF

exec pgbouncer "$dir/pgbouncer.ini"
