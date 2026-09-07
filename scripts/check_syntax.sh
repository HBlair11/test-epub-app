#!/usr/bin/env bash
# Fast syntax/type check for all Kotlin sources and Gradle scripts.
# Does NOT produce an APK. Use after small edits for a quick compile gate.
# Usage: ./scripts/check_syntax.sh
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /usr/lib/jvm/java-17-openjdk-amd64 /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    if [ -e "$candidate" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"

echo "==> Kotlin compile check (compileDebugKotlin)"
./gradlew compileDebugKotlin --console=plain --no-daemon

echo "==> Gradle build-script check (help)"
./gradlew help --console=plain --no-daemon >/dev/null

echo "==> Checking for obvious XML resource errors (mergeDebugResources)"
./gradlew mergeDebugResources --console=plain --no-daemon >/dev/null

echo "==> Syntax check passed"
