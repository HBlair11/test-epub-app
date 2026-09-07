#!/usr/bin/env bash
# Build The Livre Magicae debug APK without Android Studio.
# Usage: ./scripts/build.sh
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# Prefer JAVA_HOME if set; otherwise try common JDK 17 locations.
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /usr/lib/jvm/java-17-openjdk-amd64 /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    if [ -e "$candidate" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"

echo "==> JAVA_HOME=$JAVA_HOME"
echo "==> ANDROID_HOME=$ANDROID_HOME"
echo "==> Building debug APK…"

./gradlew assembleDebug --console=plain --no-daemon --stacktrace

APK="app/build/outputs/apk/debug/the-livre-magicae.apk"
if [ -f "$APK" ]; then
  echo "==> APK built: $APK ($(du -h "$APK" | cut -f1))"
else
  echo "ERROR: expected APK not found at $APK" >&2
  exit 1
fi
