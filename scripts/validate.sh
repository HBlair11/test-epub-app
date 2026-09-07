#!/usr/bin/env bash
# Full validation pipeline for The Livre Magicae.
# Runs: clean -> Kotlin compile (syntax) -> unit tests -> debug APK -> APK inspection.
# Exits non-zero if any step fails.
# Usage: ./scripts/validate.sh
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /usr/lib/jvm/java-17-openjdk-amd64 /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    if [ -e "$candidate" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"

AAPT2="${ANDROID_HOME}/build-tools/35.0.0/aapt2"
APK="app/build/outputs/apk/debug/the-livre-magicae.apk"

step() { printf "\n\033[1m==> %s\033[0m\n" "$1"; }

step "1/6  Clean"
./gradlew clean --console=plain --no-daemon >/dev/null

step "2/6  Kotlin compile (syntax + type check)"
./gradlew compileDebugKotlin --console=plain --no-daemon

step "3/6  Unit tests (EPUB parser)"
./gradlew testDebugUnitTest --console=plain --no-daemon

step "4/6  Build debug APK"
./gradlew assembleDebug --console=plain --no-daemon

step "5/6  Inspect APK: permissions (must NOT include INTERNET)"
if [ -x "$AAPT2" ]; then
  PERMS="$("$AAPT2" dump permissions "$APK" 2>/dev/null || true)"
  echo "$PERMS"
  if echo "$PERMS" | grep -qi "android.permission.INTERNET"; then
    echo "FAIL: APK declares INTERNET permission" >&2
    exit 1
  fi
else
  echo "WARN: aapt2 not found at $AAPT2 — skipping permission check" >&2
fi

step "6/6  Inspect APK: badging"
if [ -x "$AAPT2" ]; then
  "$AAPT2" dump badging "$APK" 2>/dev/null | grep -E "package:|sdkVersion|targetSdkVersion|application-label:" || true
fi

printf "\n\033[1;32m==> VALIDATION PASSED\033[0m\n"
