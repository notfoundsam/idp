#!/bin/sh
# Runs on every `up`: a restarted server starts sealed, and dev mode would lose keys that Postgres still depends on.
set -eu

STATE=/openbao/file/local-init
TOKEN=local-dev-token

# `bao status` exits with 2 while the server is sealed, which still means it is reachable.
attempts=0
until bao status >/dev/null 2>&1 || [ $? -eq 2 ]; do
  attempts=$((attempts + 1))
  if [ "$attempts" -gt 60 ]; then
    echo "OpenBao did not start" >&2
    exit 1
  fi
  sleep 0.5
done

if ! bao status -format=json | grep -q '"initialized": true'; then
  bao operator init -key-shares=1 -key-threshold=1 >"$STATE"
fi

bao operator unseal "$(sed -n 's/^Unseal Key 1: //p' "$STATE")" >/dev/null

BAO_TOKEN="$(sed -n 's/^Initial Root Token: //p' "$STATE")"
export BAO_TOKEN

# Raft needs a moment after unseal to elect a leader.
attempts=0
until bao token lookup >/dev/null 2>&1; do
  attempts=$((attempts + 1))
  if [ "$attempts" -gt 60 ]; then
    echo "OpenBao has no active node" >&2
    exit 1
  fi
  sleep 0.5
done

bao token lookup "$TOKEN" >/dev/null 2>&1 || bao token create -id="$TOKEN" -policy=root -orphan >/dev/null
bao secrets list | grep -q '^transit/' || bao secrets enable transit >/dev/null
