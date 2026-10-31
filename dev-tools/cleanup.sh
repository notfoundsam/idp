#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/domains.sh"

for domain in "${DOMAINS[@]}"; do
  if grep -qE "^127\.0\.0\.1[[:space:]]+$domain\$" /etc/hosts; then
    sudo sed -i.backup "/^127\.0\.0\.1[[:space:]]*$domain\$/d" /etc/hosts
    echo "Removed: $domain"
  fi
done

rm -rf "$SCRIPT_DIR/certs"
echo "Removed certificates"

echo "The mkcert CA may be used by other projects."
read -r -p "Uninstall it? (y/N): " answer
if [[ "$answer" =~ ^[Yy]$ ]]; then
  mkcert -uninstall
fi
