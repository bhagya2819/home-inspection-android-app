#!/bin/bash
# Builds the signed release APK for handing out (PRD §14) and prints what to publish with it.
# Refuses to produce a debug-signed APK: a phone that installs one can't take a properly signed update
# without being reinstalled (and losing unsynced work).
# Usage: tools/release.sh        → dist/Griha-<version>.apk + dist/Griha-<version>.sha256
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
PROPS="${GRIHA_SIGNING:-$HOME/.griha/release-signing.properties}"
[ -f "$PROPS" ] || { echo "No $PROPS — create the release key first (see docs/release-key.md)." >&2; exit 1; }

./gradlew -q :app:assembleRelease -Pgriha.signing="$PROPS"
./gradlew -q --stop >/dev/null 2>&1 || true

APK=app/build/outputs/apk/release/app-release.apk
APKSIGNER=$(ls -d "$HOME"/Library/Android/sdk/build-tools/*/apksigner | sort -V | tail -1)
CERT=$("$APKSIGNER" verify --print-certs "$APK" | grep "certificate SHA-256 digest" | head -1 | sed 's/.*: //')
if "$APKSIGNER" verify --print-certs "$APK" | grep -q "CN=Android Debug"; then
  echo "Refusing: the APK is debug-signed." >&2; exit 1
fi
VERSION=$(grep -m1 'versionName = ' app/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')
CODE=$(grep -m1 'versionCode = ' app/build.gradle.kts | sed 's/[^0-9]//g')
mkdir -p dist
OUT="dist/Griha-$VERSION.apk"
cp "$APK" "$OUT"
SHA=$(shasum -a 256 "$OUT" | cut -d' ' -f1)
echo "$SHA  Griha-$VERSION.apk" > "dist/Griha-$VERSION.sha256"
cat <<EOF
Built $OUT  (version $VERSION, code $CODE, $(($(stat -f %z "$OUT") / 1024)) KB)
APK SHA-256:          $SHA
Signing certificate:  $CERT
Send the APK with the release notes; keep this output with the release (docs/install-guide.md, "Checking the file").
Next release: bump versionCode (now $CODE) and versionName in app/build.gradle.kts.
EOF
