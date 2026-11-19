#!/usr/bin/env bash
# Issues the certificate of IDP_DOMAIN with the mkcert CA of this machine. For a server whose browser is the one of the laptop, run it
# on the laptop with the domain of the server and copy the two files, see "A server behind its own proxy" in dev-tools/README.md.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERTS_DIR="$SCRIPT_DIR/certs"
source "$SCRIPT_DIR/domains.sh"

if ! command -v mkcert >/dev/null; then
  echo "mkcert is required: https://github.com/FiloSottile/mkcert#installation"
  exit 1
fi

mkdir -p "$CERTS_DIR"

if [ -f "$CERTS_DIR/$IDP_DOMAIN.pem" ]; then
  echo "Certificates already exist"
  exit 0
fi

mkcert -install
mkcert -cert-file "$CERTS_DIR/$IDP_DOMAIN.pem" -key-file "$CERTS_DIR/$IDP_DOMAIN-key.pem" "$IDP_DOMAIN" "*.$IDP_DOMAIN" localhost 127.0.0.1
