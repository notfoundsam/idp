#!/usr/bin/env bash
set -euo pipefail

CERTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/certs"

if ! command -v mkcert >/dev/null; then
  echo "mkcert is required: https://github.com/FiloSottile/mkcert#installation"
  exit 1
fi

mkdir -p "$CERTS_DIR"

if [ -f "$CERTS_DIR/idp.test.pem" ]; then
  echo "Certificates already exist"
  exit 0
fi

mkcert -install
mkcert -cert-file "$CERTS_DIR/idp.test.pem" -key-file "$CERTS_DIR/idp.test-key.pem" idp.test "*.idp.test" localhost 127.0.0.1
