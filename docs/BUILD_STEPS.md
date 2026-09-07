# Building The Livre Magicae from Source

This guide reproduces the build environment and produces the APK from the
source zip. It is what you need if you start fresh on a Linux machine.

## 1. Requirements

- **JDK 17** (this project uses JDK 17; JDK 8 or 21 will not work with this
  Gradle/AGP setup). Temurin 17 is recommended.
- **Android SDK** with:
  - cmdline-tools
  - platform-tools
  - `platforms;android-35`
  - `build-tools;35.0.0`
- **Internet access** for the first Gradle dependency resolution (the app
  itself has no INTERNET permission and is fully offline once built).
- ~3 GB free disk for the SDK + Gradle cache.

The sandbox used for Patch 11 had the JDK at
`/home/user/workspace/tools/jdk-17.0.15+6` and the Android SDK at
`/home/user/workspace/tools/android-sdk/`.

## 2. Install the JDK (if you don't have JDK 17)

```bash
# Temurin 17 — example using sdkman
sdk install java 17.0.15-tem

# or download directly from Adoptium:
#   https://adoptium.net/temurin/releases/?version=17
# and extract to e.g. ~/tools/jdk-17
```

Verify:
```bash
java -version   # should print "17" somewhere
```

## 3. Install the Android SDK (command-line only)

```bash
ANDROID_HOME=~/tools/android-sdk
mkdir -p "$ANDROID_HOME"/cmdline-tools
# Download "commandline-tools" from:
#   https://developer.android.com/studio#command-line-tools-only
# Extract so that $ANDROID_HOME/cmdline-tools/latest/bin exists.

export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

sdkmanager --licenses            # accept the licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

## 4. Extract the source zip

```bash
unzip The-Livre-Magicae-Patch-11-source.zip -d patch11_src
cd patch11_src
```

The project root contains `settings.gradle.kts`, `gradlew`, `app/`, `gradle/`,
`docs/`, and `README.md`.

## 5. Build the APK

```bash
export JAVA_HOME=/path/to/jdk-17            # your JDK 17 path
export ANDROID_HOME=/path/to/android-sdk    # your Android SDK path
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew assembleDebug --no-daemon --console=plain
```

On first run, Gradle downloads dependencies and may take several minutes.
A successful build ends with:

```
BUILD SUCCESSFUL
```

The APK is produced at:

```
app/build/outputs/apk/debug/the-livre-magicae.apk
```

## 6. Install on a device / emulator

```bash
# With a device connected (USB debugging on) or an emulator running:
adb install -r app/build/outputs/apk/debug/the-livre-magicae.apk
```

The app shows as **The Livre Magicae** in the launcher. Package name is
`com.epubreader.app` (so installing Patch 11 over Patch 10 is an in-place
update — your library, reading progress, and settings are preserved).

## 7. Verify the build (optional)

```bash
AAPT=$(ls $ANDROID_HOME/build-tools/35.0.0/aapt2)
$AAPT dump badging app/build/outputs/apk/debug/the-livre-magicae.apk | \
  grep -E "application-label:|versionCode|versionName|package:|uses-permission"
```

Expected:
```
package: name='com.epubreader.app' versionCode='11' versionName='1.11-patch11' ...
application-label:'The Livre Magicae'
```
and **no** `uses-permission: name='android.permission.INTERNET'` line.

## 8. Run the unit tests (optional)

```bash
./gradlew testDebugUnitTest --no-daemon --console=plain
```

Expected: 18 / 20 pass. The 2 failures (`EpubParserTest.parsesRealEpub`,
`EpubParserTest.searchesRealEpub`) require a test fixture file
`/home/user/workspace/sample.epub` that is not checked into the repo. They are
unrelated to any patch change.

## 9. Building a release (signed) APK

The source ships a debug build setup. To produce a signed release:

1. Generate a keystore (once):
   ```bash
   keytool -genkey -v -keystore livre-release.jks \
     -keyalg RSA -keysize 2048 -validity 10000 -alias livre
   ```
2. Add a signing config to `app/build.gradle.kts` under
   `android { signingConfigs { ... } }` reading the keystore path/password from
   environment variables or `local.properties`.
3. `./gradlew assembleRelease --no-daemon --console=plain`

For personal use, the debug APK is fine — it is installable on any device
with "Install unknown apps" enabled for your file manager.

## Common problems

| Symptom | Cause / fix |
|--------|-------------|
| `Unsupported class file major version` | You are not using JDK 17. Install Temurin 17 and set `JAVA_HOME`. |
| `SDK location not found` | `ANDROID_HOME` not exported, or no `local.properties` with `sdk.dir`. |
| `Failed to install ... INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A different signing key is installed. Uninstall the old app first: `adb uninstall com.epubreader.app`. |
| `the-livre-magicae.apk` not found | Build failed — re-run without `grep` to see the error, or check that `applicationVariants` rename rule is in `app/build.gradle.kts`. |
