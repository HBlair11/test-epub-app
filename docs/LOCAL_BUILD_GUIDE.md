# Local Headless Build Guide (Patch 16+)

This is the exact, reproducible recipe used to build the Patch 16 debug APK
**without Android Studio**, in a plain Linux userspace (no root). Reuse it for any
future patch.

## Toolchain versions (do not change without checking compatibility)

| Component | Version | Source |
|---|---|---|
| JDK | 17 (Temurin 17.0.12) | Adoptium GitHub release |
| Gradle | 8.9 (project wrapper) | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.7.2 | `app/build.gradle.kts` plugins block |
| Kotlin | 2.0.20 | same |
| KSP | 2.0.20-1.0.25 | same |
| Android platform | android-35 | sdkmanager |
| Build-tools | 35.0.0 | sdkmanager |
| minSdk / targetSdk / compileSdk | 24 / 35 / 35 | `app/build.gradle.kts` |

AGP 8.7.x requires JDK 17. **Do not build with JDK 21+** — Gradle 8.9 + AGP 8.7.2
is validated against JDK 17. (JDK 25 will be rejected by the wrapper.)

## One-time environment setup

Run these once per fresh sandbox/machine. They install JDK 17 and the Android
command-line tools into `~/workspace/build-env` (no sudo):

```bash
mkdir -p ~/workspace/build-env && cd ~/workspace/build-env

# 1) JDK 17 (Temurin)
curl -sL -o jdk17.tar.gz \
  "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12%2B7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.12_7.tar.gz"
tar xzf jdk17.tar.gz

# 2) Android command-line tools
curl -sL -o cmdline-tools.zip \
  "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip -q cmdline-tools.zip
mkdir -p android-sdk/cmdline-tools
mv cmdline-tools android-sdk/cmdline-tools/latest
```

## Environment variables (set before every build)

```bash
export JAVA_HOME=$HOME/workspace/build-env/jdk-17.0.12+7
export ANDROID_HOME=$HOME/workspace/build-env/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
```

## Install SDK components (only needed once; persisted under android-sdk)

```bash
yes | sdkmanager --licenses > /dev/null 2>&1 || true
yes | sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

## Build the debug APK

```bash
cd /home/user/workspace/patch16/the-livre-magicae   # or your patch dir
chmod +x gradlew
./gradlew assembleDebug --no-daemon --console=plain
```

The APK is emitted at:

```
app/build/outputs/apk/debug/the-livre-magicae.apk
```

(the `applicationVariants` rename block in `app/build.gradle.kts` pins this name).

### Forcing a real recompile

Gradle's build cache can make a build report `UP-TO-DATE` / `FROM-CACHE` even
when you want a from-scratch verification. To force a genuine recompile (use this
to prove your edits actually compile):

```bash
./gradlew clean assembleDebug --no-daemon --console=plain --no-build-cache --rerun-tasks
```

Watch for `> Task :app:compileDebugKotlin` to execute (not `UP-TO-DATE` /
`FROM-CACHE`). The only acceptable warnings are the two pre-existing
`statusBarColor` / `navigationBarColor` deprecations in `ReaderActivity.kt`.

## Verifying the APK before delivery

```bash
AAPT=$ANDROID_HOME/build-tools/35.0.0/aapt2

# Version + package
$AAPT dump badging app/build/outputs/apk/debug/the-livre-magicae.apk | grep -E "versionCode|versionName|package:"

# Permissions — must be ONLY DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, never INTERNET
$AAPT dump permissions app/build/outputs/apk/debug/the-livre-magicae.apk

# Zip integrity
unzip -t app/build/outputs/apk/debug/the-livre-magicae.apk > /dev/null 2>&1 && echo OK

# Confirm your new classes made it into the dex
unzip -o -q app/build/outputs/apk/debug/the-livre-magicae.apk classes*.dex -d /tmp/apkx
for d in /tmp/apkx/classes*.dex; do $ANDROID_HOME/build-tools/35.0.0/dexdump "$d"; done \
  | grep -E "Class descriptor" | grep -i "YourNewClassName"
```

## CI (unchanged)

The GitHub Actions workflows are the canonical build path and are **not** modified
by Patch 16:

- `.github/workflows/android-ci.yml` — builds the debug APK on every push/PR
  (ubuntu-latest, pre-installed Android SDK, JDK 17).
- `.github/workflows/release.yml` — builds + signs the release APK on tag push.

The `android-ci.yml` workflow asserts **no `INTERNET` permission** is present; the
local build above must also satisfy that check (and does).

## Packaging the source zip for delivery

```bash
cd /home/user/workspace/patch16
zip -r the-livre-magicae-patch-16.zip the-livre-magicae \
  -x "*/.git/*" "*/.gradle/*" "*/.kotlin/*" "*/build/*" \
     "*/local.properties" "*/.idea/*" "*.iml"
```

Always exclude `.git`, `.gradle`, `.kotlin`, `build/`, and `local.properties` so the
zip contains only source + docs + gradle wrapper, and so it builds cleanly for the
recipient.
