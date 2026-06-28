#!/usr/bin/env bash
# Fetches the upstream proxy/ directory from Flowseal/tg-ws-proxy at a pinned commit
# into app/src/main/python/proxy. NOT vendored in git — mirrors the HA add-on model.
set -euo pipefail

# Flowseal/tg-ws-proxy — pinned to release v1.8.1 (keep in sync with the HA add-on Dockerfile)
REF="4f9edf3072454dcb18831e65e7cfdb68f7046a61"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/python/proxy"
TMP="$(mktemp -d)"

git clone --no-checkout https://github.com/Flowseal/tg-ws-proxy.git "$TMP"
git -C "$TMP" checkout "$REF" -- proxy LICENSE

rm -rf "$DEST"; mkdir -p "$DEST"
cp -r "$TMP/proxy/." "$DEST/"
cp "$TMP/LICENSE" "$DEST/LICENSE-UPSTREAM"

rm -rf "$TMP"
echo "Fetched proxy/ from Flowseal/tg-ws-proxy@$REF -> $DEST"
