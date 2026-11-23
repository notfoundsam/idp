# Sourced by dns-mapping.sh, cleanup.sh, ssl-setup.sh and smoke.sh. IDP_DOMAIN comes from .env through the Makefile.
IDP_DOMAIN="${IDP_DOMAIN:-idp.test}"
DOMAINS=(
  "$IDP_DOMAIN"
  "acme.$IDP_DOMAIN"
  "globex.$IDP_DOMAIN"
  "ops.$IDP_DOMAIN"
  "ops-sso.$IDP_DOMAIN"
  "release-smoke.$IDP_DOMAIN"
  "mail.$IDP_DOMAIN"
  "sp.$IDP_DOMAIN"
  "dashboard.$IDP_DOMAIN"
  "grafana.$IDP_DOMAIN"
)
