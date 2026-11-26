#!/usr/bin/env bash
# Builds a self-contained release package WITHOUT GitHub Actions: signed APK (if signing env is set), checksums,
# R8 mapping file, source archive and a manifest. Run from the repo root on a machine with JDK 17 + Android SDK.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-release-package}"
VER=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
COMMIT=$(git rev-parse HEAD)
rm -rf "$OUT"; mkdir -p "$OUT"

./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleRelease

if [ -n "${ANDROID_KEYSTORE_PATH:-}" ]; then SIGNED=signed; else SIGNED=UNSIGNED-do-not-distribute; fi
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
cp "$APK" "$OUT/yeehaw-$VER-$CODE-$SIGNED.apk"
cp app/build/outputs/mapping/release/mapping.txt "$OUT/r8-mapping-$VER-$CODE.txt"   # needed to read release crash traces
git archive --format=tar.gz --prefix="yeehaw.pro-$COMMIT/" -o "$OUT/source-$COMMIT.tar.gz" "$COMMIT"
git bundle create "$OUT/repo-$COMMIT.bundle" --all >/dev/null 2>&1 || true            # full history backup

( cd "$OUT" && sha256sum * > SHA256SUMS.txt )
cat > "$OUT/MANIFEST.txt" <<M
YeeHaw Browser $VER (versionCode $CODE)
commit:   $COMMIT
built:    $(date -u +%Y-%m-%dT%H:%M:%SZ)
signing:  $SIGNED
note:     verify with: sha256sum -c SHA256SUMS.txt ; apksigner verify --verbose <apk>
M
echo "Package written to $OUT/"; cat "$OUT/MANIFEST.txt"
