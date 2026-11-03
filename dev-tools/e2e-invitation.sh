#!/usr/bin/env bash
# Gives the e2e tests fresh invitations, since each one works once. The operator CLI reinvites every contact below as an admin of its
# tenant, which first creates the user and later turns it back into an invited admin without factors. So `operator@ops-sso.test`
# loses the passkeys it had. Older mails to an address go first, so a test finds the new one. Mailpit keeps mail only until it is
# recreated, which this also covers.
set -euo pipefail

cd "$(dirname "$0")/.."
ca="$(mkcert -CAROOT)/rootCA.pem"

reinvite() {
  local domain=$1 contact=$2 tenant
  tenant="$(docker compose exec -T postgres psql -U postgres -d idp -tAc \
    "select tenant_id from platform.tenant_domains where domain = '$domain'")"
  [ -n "$tenant" ] || { echo "$domain is not provisioned, run make up" >&2; exit 1; }

  curl --fail --silent --show-error --max-time 10 --cacert "$ca" -o /dev/null -X DELETE \
    "https://mail.idp.test/api/v1/search?query=to:%22$contact%22"

  docker compose run --rm --no-deps -e SPRING_MAIN_BANNER_MODE=off -e LOGGING_LEVEL_ROOT=WARN \
    ops java -jar /app/service.jar cli reinvite "$tenant" "$contact"
}

reinvite acme.idp.test invitee@acme.test
reinvite acme.idp.test passkey@acme.test
reinvite acme.idp.test account@acme.test
reinvite ops-sso.idp.test operator@ops-sso.test
reinvite ops-sso.idp.test outsider@ops-sso.test
