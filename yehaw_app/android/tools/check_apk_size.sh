#!/usr/bin/env bash
# Fails the build if the release APK exceeds the 12 MB budget from the product directive.
set -euo pipefail
APK="${1:?usage: check_apk_size.sh path/to/app.apk}"
LIMIT=$((12 * 1024 * 1024))
SIZE=$(stat -c%s "$APK" 2>/dev/null || stat -f%z "$APK")
echo "APK size: $SIZE bytes (limit $LIMIT)"
[ "$SIZE" -le "$LIMIT" ] || { echo "APK exceeds 12 MB budget"; exit 1; }
