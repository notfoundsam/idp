#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/domains.sh"

for domain in "${DOMAINS[@]}"; do
  if grep -qE "^127\.0\.0\.1[[:space:]]+$domain\$" /etc/hosts; then
    echo "Already mapped: $domain"
  else
    echo "127.0.0.1 $domain" | sudo tee -a /etc/hosts >/dev/null
    echo "Added: $domain"
  fi
done
