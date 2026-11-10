#!/usr/bin/env bash
# Sets up the synthetic tenant of the release smoke test locally, the way docs/release.md does it once per environment: the operator
# CLI submits and approves release-smoke, then dev-tools/release-smoke.mjs walks what a person does in the console. The login of the
# synthetic user lands in dev-tools/release-smoke/login.json, which `make smoke-sso` hands the smoke test. A second run with that file
# in place changes nothing, so delete it to set the tenant up again, for example after `make clean`.
set -euo pipefail

cd "$(dirname "$0")/.."
domain=release-smoke.idp.test
admin=admin@release-smoke.test
login=dev-tools/release-smoke/login.json

if [ -f "$login" ]; then
  echo "$login exists, delete it to set the synthetic tenant up again"
  exit 0
fi
mkdir -p "$(dirname "$login")"

cli() {
  docker compose run --rm --no-deps -e SPRING_MAIN_BANNER_MODE=off -e LOGGING_LEVEL_ROOT=WARN ops java -jar /app/service.jar cli "$@"
}

sql() {
  docker compose exec -T postgres psql -U postgres -d idp -tAc "$1"
}

tenant="$(sql "select tenant_id from platform.tenant_domains where domain = '$domain'")"
if [ -z "$tenant" ]; then
  application="$(cli submit "Release smoke test" release-smoke "$admin" | awk '/^application / { print $2 }')"
  cli approve "$application"
  for _ in $(seq 1 60); do
    tenant="$(sql "select tenant_id from platform.tenant_domains where domain = '$domain'")"
    [ -n "$tenant" ] && [ "$(sql "select status from platform.tenants where id = '$tenant'")" = ACTIVE ] && break
    sleep 2
  done
  [ -n "$tenant" ] || { echo "release-smoke was not provisioned" >&2; exit 1; }
fi

# A fresh invitation of the first admin, so the run needs no password from before.
since="$(($(date +%s) * 1000))"
cli reinvite "$tenant" "$admin"

cp "$(mkcert -CAROOT)/rootCA.pem" dev-tools/certs/rootCA.pem
docker compose run --rm --no-deps release-smoke node dev-tools/release-smoke.mjs \
  --host "$domain" --admin "$admin" --since "$since" --out "$login"
