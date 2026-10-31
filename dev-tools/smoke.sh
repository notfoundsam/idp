#!/usr/bin/env bash
# Uses --resolve, so it does not depend on /etc/hosts.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CA="$(mkcert -CAROOT)/rootCA.pem"
failed=0

check() {
  local name="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "ok   $name"
  else
    echo "FAIL $name"
    failed=1
  fi
}

status() {
  local host="$1" path="$2"
  curl -s -o /dev/null -w '%{http_code}' --cacert "$CA" --resolve "$host:443:127.0.0.1" "https://$host$path"
}

expect_status() {
  [ "$(status "$1" "$2")" = "$3" ]
}

http_redirects() {
  [ "$(curl -s -o /dev/null -w '%{redirect_url}' --resolve mail.idp.test:80:127.0.0.1 http://mail.idp.test/)" = "https://mail.idp.test/" ]
}

# Spring answers with a JSON 404, Traefik with plain text, and the tenant filter with an empty body.
reaches_spring() {
  curl -s --cacert "$CA" --resolve "$1:443:127.0.0.1" "https://$1/api" -H 'Accept: application/json' | grep -q '"status":404'
}

# A healthy container is not routed yet: Traefik picks it up about a second later.
wait_for_routes() {
  for _ in $(seq 1 15); do
    reaches_spring acme.idp.test && reaches_spring ops.idp.test && return
    sleep 1
  done
}

unknown_tenant_refused() {
  local host=nobody.idp.test
  [ "$(status "$host" /api)" = "404" ] &&
    [ -z "$(curl -s --cacert "$CA" --resolve "$host:443:127.0.0.1" "https://$host/api")" ]
}

bao() {
  docker compose exec -T -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=local-dev-token openbao bao "$@"
}

transit_roundtrip() {
  bao write -f transit/keys/smoke >/dev/null
  local cipher
  cipher=$(bao write -field=ciphertext transit/encrypt/smoke plaintext="$(printf 'smoke' | base64)")
  [ "$(bao write -field=plaintext transit/decrypt/smoke ciphertext="$cipher" | base64 -d)" = "smoke" ]
}

pgbouncer_query() {
  docker compose exec -T -e PGPASSWORD=idp_owner postgres psql -h pgbouncer -p 5432 -U idp_owner -d idp -tAc 'select 1' | grep -q '^1$'
}

platform_migrated() {
  [ "$(docker compose exec -T postgres psql -U postgres -d idp -tAc 'select count(*) from platform.flyway_schema_history where success')" -gt 0 ]
}

runtime_role_connects() {
  docker compose exec -T -e PGPASSWORD=app_runtime postgres psql -h postgres -U app_runtime -d idp -tAc 'select count(*) from platform.tenants' | grep -q '^[0-9]'
}

health() {
  docker compose exec -T "$1" curl -fs http://localhost:8081/actuator/health | grep -q '"status":"UP"'
}

wait_for_routes

check "http redirects to https" http_redirects
check "mail.idp.test" expect_status mail.idp.test / 200
check "dashboard.idp.test" expect_status dashboard.idp.test / 200
check "sp.idp.test" expect_status sp.idp.test /simplesaml/module.php/core/welcome 200
check "acme.idp.test reaches app" reaches_spring acme.idp.test
check "globex.idp.test reaches app" reaches_spring globex.idp.test
check "unknown tenant refused" unknown_tenant_refused
check "ops.idp.test reaches ops" reaches_spring ops.idp.test
check "unknown path stays on Traefik" expect_status acme.idp.test /nothing 404
check "postgres through pgbouncer" pgbouncer_query
check "platform schema migrated" platform_migrated
check "runtime role connects" runtime_role_connects
check "valkey" docker compose exec -T valkey valkey-cli ping
check "openbao transit round trip" transit_roundtrip
check "app health" health app
check "worker health" health worker
check "ops health" health ops

exit "$failed"
