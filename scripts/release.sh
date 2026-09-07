#!/usr/bin/env bash
# Build a release APK for The Livre Magicae.
# By default this builds the release variant (debug-signed unless a
# keystore.properties is present — see docs/BUILD_AND_VALIDATION.md).
# Usage: ./scripts/release.sh
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

if [ ! -f "keystore.properties" ]; then
  echo "ERROR: keystore.properties is required for a production release build." >&2
  echo "See docs/BUILD_AND_VALIDATION.md for setup instructions." >&2
  exit 1
fi

if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /usr/lib/jvm/java-17-openjdk-amd64 /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    if [ -e "$candidate" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"

mkdir -p release

echo "==> Building release APK…"
./gradlew assembleRelease --console=plain --no-daemon --stacktrace


APK="app/build/outputs/apk/release/the-livre-magicae.apk"
OUT="release/the-livre-magicae-release.apk"
APKSIGNER="$(find "${ANDROID_HOME}/build-tools" -type f -name apksigner -print 2>/dev/null | sort -V | tail -n 1)"
if [ -z "$APKSIGNER" ]; then
  echo "ERROR: apksigner was not found in Android SDK Build Tools." >&2
  exit 1
fi
"$APKSIGNER" verify --print-certs "$APK"

cp "$APK" "$OUT"

# Read version from the gradle file for the filename if possible
VERSION="$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | sed 's/versionName = //;s/"//g' || echo unknown)"
if [ "$VERSION" != "unknown" ]; then
  cp "$APK" "release/the-livre-magicae-release-${VERSION}.apk"
  echo "==> Also copied to release/the-livre-magicae-release-${VERSION}.apk"
fi

echo "==> Release APK: $OUT ($(du -h "$OUT" | cut -f1))"
