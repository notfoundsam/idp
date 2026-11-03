#!/usr/bin/env bash
# Hands SimpleSAMLphp the metadata of the local tenant as the IdP publishes it. The signing certificate is created when the tenant is
# provisioned, so run this after `make up` on fresh volumes, and again after a key rotation.
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p metadata
ca="$(mkcert -CAROOT)/rootCA.pem"

for tenant in acme; do
  curl --fail --silent --show-error --max-time 10 \
    --cacert "$ca" \
    "https://$tenant.idp.test/saml/metadata" -o "metadata/$tenant.xml.tmp"
  mv "metadata/$tenant.xml.tmp" "metadata/$tenant.xml"
  echo "metadata/$tenant.xml"
done
