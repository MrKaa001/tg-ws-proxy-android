#!/usr/bin/env bash
# Build the signed release APK and publish it as a GitHub Release — the self-update
# channel the app's Updater reads. The APK embeds a read-only PAT; publishing needs a
# write PAT — both live in update.properties (gitignored, see update.properties.example).
# Git push itself goes over SSH (key-based).
#
# Usage:  bash tools/release.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
cd "$ROOT"

# ── config ──
PROPS="$ROOT/update.properties"
[ -f "$PROPS" ] || { echo "update.properties missing — copy update.properties.example"; exit 1; }
prop() { sed -n "s/^$1=//p" "$PROPS" | head -n1; }
OWNER="$(prop github.owner)"
REPO="$(prop github.repo)"
RTOKEN="$(prop github.release_token)"
[ -n "$OWNER" ] && [ -n "$REPO" ] && [ -n "$RTOKEN" ] || {
  echo "update.properties: github.owner / github.repo / github.release_token not filled"; exit 1;
}

# ── the release must point at a commit — refuse dirty trees ──
[ -z "$(git status --porcelain)" ] || { echo "Working tree dirty — commit first"; exit 1; }

VERSION="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -n1)"
TAG="v$VERSION"
APK="app/build/outputs/apk/release/tg-ws-proxy-$VERSION.apk"
echo "Releasing $TAG"

# ── build (Gradle 8.7 can't run on JDK 25+ — pin the scoop JDK 17 when present) ──
if [ -d "$HOME/scoop/apps/temurin17-jdk/current" ]; then
  export JAVA_HOME="$HOME/scoop/apps/temurin17-jdk/current"
fi
./gradlew assembleRelease
[ -f "$APK" ] || { echo "APK not found: $APK"; exit 1; }
SIZE="$(stat -c%s "$APK")"

# ── push the commit the release will point at ──
git push -u origin main

# ── create the release (tag is created remotely at main) ──
API="https://api.github.com/repos/$OWNER/$REPO"
AUTH="Authorization: Bearer $RTOKEN"
RELEASE_ID="$(curl -fsS -X POST "$API/releases" \
  -H "$AUTH" -H "Accept: application/vnd.github+json" \
  -d "{\"tag_name\":\"$TAG\",\"target_commitish\":\"main\",\"name\":\"$TAG\"}" \
  | python -c 'import json,sys; print(json.load(sys.stdin)["id"])')"

# ── upload the APK asset ──
curl -fsS -X POST \
  "https://uploads.github.com/repos/$OWNER/$REPO/releases/$RELEASE_ID/assets?name=tg-ws-proxy-$VERSION.apk" \
  -H "$AUTH" -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$APK" > /dev/null

# ── verify: /releases/latest serves our tag and the exact APK size ──
curl -fsS "$API/releases/latest" -H "$AUTH" -H "Accept: application/vnd.github+json" \
  | TAG="$TAG" SIZE="$SIZE" python -c '
import json, os, sys
r = json.load(sys.stdin)
assert r["tag_name"] == os.environ["TAG"], r["tag_name"]
apk = [a for a in r["assets"] if a["name"].endswith(".apk")]
assert len(apk) == 1, "no .apk asset on the release"
assert apk[0]["size"] == int(os.environ["SIZE"]), apk[0]["size"]
print("latest == %s, asset %d bytes — OK" % (r["tag_name"], apk[0]["size"]))
'
echo "Done: https://github.com/$OWNER/$REPO/releases/tag/$TAG"
