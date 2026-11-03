#!/usr/bin/env bash
# Gives the e2e tests a fresh invitation, since each one works once. The operator CLI reinvites invitee@acme.test as an admin of acme,
# which first creates the user and later turns it back into an invited admin without factors. Older mails to the address go first,
# so the test finds the new one.
set -euo pipefail

cd "$(dirname "$0")/.."
contact=invitee@acme.test
ca="$(mkcert -CAROOT)/rootCA.pem"

tenant="$(docker compose exec -T postgres psql -U postgres -d idp -tAc \
  "select tenant_id from platform.tenant_domains where domain = 'acme.idp.test'")"
[ -n "$tenant" ] || { echo "acme is not provisioned, run make up" >&2; exit 1; }

curl --fail --silent --show-error --max-time 10 --cacert "$ca" -o /dev/null -X DELETE \
  "https://mail.idp.test/api/v1/search?query=to:%22$contact%22"

docker compose run --rm --no-deps -e SPRING_MAIN_BANNER_MODE=off -e LOGGING_LEVEL_ROOT=WARN \
  ops java -jar /app/service.jar cli reinvite "$tenant" "$contact"
