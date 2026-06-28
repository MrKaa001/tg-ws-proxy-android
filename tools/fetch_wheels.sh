#!/usr/bin/env bash
# Pre-fetch the Chaquopy native wheels the build needs into wheels/ (git-ignored).
# Chaquopy's pip pulls these from chaquo.com, which is DPI-throttled on some networks
# (the very problem this app exists to bypass) — sustained transfers get RST'd, so we
# grind them down in small ranges via grind_get.sh and serve them with --find-links.
#
# Run once on a fresh checkout before `./gradlew assembleDebug`:
#   bash tools/fetch_wheels.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
WH="$ROOT/wheels"
REPO="https://chaquo.com/pypi-13.1"   # Chaquopy 16.x package index
mkdir -p "$WH"

# name -> repo path (cp311 / arm64-v8a, matching app/build.gradle python.version + abiFilters)
fetch() {
  local sub="$1" file="$2"
  if [ -f "$WH/$file" ] && unzip -t "$WH/$file" >/dev/null 2>&1; then
    echo "have  $file"
    return
  fi
  echo "fetch $file"
  bash "$HERE/grind_get.sh" "$REPO/$sub/$file" "$WH/$file" 16384
}

fetch cryptography   "cryptography-42.0.8-0-cp311-cp311-android_24_arm64_v8a.whl"
fetch cffi           "cffi-1.17.1-1-cp311-cp311-android_24_arm64_v8a.whl"
fetch chaquopy-libffi "chaquopy_libffi-3.3-3-py3-none-android_24_arm64_v8a.whl"
# pycparser is pure-python and comes from pypi.org (not throttled) — pip fetches it itself.

echo "all wheels present and valid in wheels/"
