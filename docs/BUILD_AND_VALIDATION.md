# Build & Validation

Everything needed to compile, check, and release Caesura — without Android Studio.

## Prerequisites

| Tool        | Version | Notes                                                        |
|-------------|---------|--------------------------------------------------------------|
| JDK         | 17      | Required (AGP 8.7 needs JDK 17). Do **not** use 21 for KSP.  |
| Android SDK | 35      | `platforms;android-35`, `build-tools;35.0.0`, `platform-tools` |
| Gradle      | 8.9     | Provided by the wrapper (`./gradlew`). Don't install manually. |

Install the SDK command-line tools and accept licenses:
```bash
export ANDROID_HOME=/path/to/android-sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
    "platform-tools" "platforms;android-35" "build-tools;35.0.0"
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
```

The project reads `ANDROID_HOME` from the environment, so you do **not** need
`local.properties` when building via the scripts or CI. (If you open in Android
Studio, it generates `local.properties` automatically.)

---

## 1. Syntax & compile checks

### Kotlin / .kt / .kts syntax check
Kotlin compiles as part of the build. To run a fast compile-only check:
```bash
./gradlew compileDebugKotlin --console=plain --no-daemon
```
This type-checks every `.kt` file (including build scripts are checked when
`./gradlew help` runs). Failures print `e: file://... message`.

### KSP (Room, Glide) processor check
Room schema validation and Glide codegen run during `kspDebugKotlin`. A schema
mistake (e.g. a DAO query referencing a missing column) fails the build here.

### Resource / XML check
`processDebugResources` validates every `res/` XML file and all `@string`/`@color`
references. Missing resources fail here (e.g. "resource string/foo not found").

### JavaScript check (reader pagination)
There is **no separate JS/TS toolchain** — the reader's pagination script is a
Kotlin string (`PAGINATION_JS` in `ReaderActivity.kt`) injected into the WebView.
Syntax errors surface at runtime in `onPageFinished` / tap handlers, not at build.
To sanity-check the JS logic, the parser unit test covers chapter rendering.

---

## 2. Unit tests

```bash
./gradlew testDebugUnitTest --console=plain --no-daemon
```
Runs the self-contained JVM regression suite in `app/src/test`. The parser tests
construct small EPUB ZIPs in memory/on disk, so CI does not depend on a developer
path such as `/home/user/workspace/sample.epub`. This suite covers EPUB metadata,
import identity rules, page mapping, reader progress math, and rescan behavior.

---

## 3. Build the debug APK

```bash
./gradlew assembleDebug --console=plain --no-daemon
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

The helper script does env setup + build in one step:
```bash
./scripts/build.sh
```

---

## 4. APK inspection (verify no INTERNET, confirm contents)

```bash
# List declared permissions — must NOT include android.permission.INTERNET
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk

# App name, package, SDK levels
aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | grep -E "package:|sdkVersion|targetSdk|application-label"
```
Expected: only `com.epubreader.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
(a default AndroidX permission). No INTERNET, no ACCESS_NETWORK_STATE.

---

## 5. Install on a device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Launch
adb shell am start -n com.epubreader.app/.MainActivity
```

---

## 6. Full validation in one command

```bash
./scripts/validate.sh
```
This runs, in order: clean → compileDebugKotlin (syntax) → testDebugUnitTest
(unit tests) → assembleDebug (APK) → aapt2 permission/badging inspection. Exits
non-zero if any step fails.

---

## 7. Release build

### One-command release (debug-signed)
```bash
./scripts/release.sh
```
Builds `assembleRelease` and copies the APK to `release/caesura-release.apk`.
The release build type currently has `isMinifyEnabled = false` (no ProGuard
shrinking) for maximum compatibility.

### Production signing with your own key (required for release distribution)

The debug APK continues to use the permanent debug keystore so patch installs can
upgrade cleanly during development. Release APKs are now signed only when a local
`keystore.properties` file is present. This file is gitignored and must never be
committed.

1. Generate or use the **permanent production keystore** that you intend to keep
   for the lifetime of the app. Back it up securely. Losing it means future APKs
   cannot update an installed production app.
2. Create `keystore.properties` in the project root:
   ```text
   storeFile=app/the-livre-magicae-release.jks
   storePassword=********
   keyAlias=the-livre-magicae
   keyPassword=********
   ```
3. Put the matching keystore at the configured path. The keystore itself is also
   gitignored.
4. Run `./scripts/release.sh`. The script refuses to build a production release
   without `keystore.properties` and verifies the APK with `apksigner`.

For GitHub Actions, see `docs/PRODUCTION_SIGNING.md`. The release workflow expects
these repository secrets:

- `EPUB_APP_RELEASE_KEYSTORE_BASE64`
- `EPUB_APP_RELEASE_KEYSTORE_PASSWORD`
- `EPUB_APP_RELEASE_KEY_ALIAS`
- `EPUB_APP_RELEASE_KEY_PASSWORD`

Never put a keystore password or private key in source code, README files, Gradle
files, or workflow YAML.

## 8. Bumping the version

In `app/build.gradle.kts` → `defaultConfig`:
```kotlin
versionCode = 2      // increment every release
versionName = "1.1"  // human-readable
```
`versionCode` must always increase for installs to upgrade cleanly.

---

## 9. CI/CD (GitHub Actions)

See `.github/workflows/`:
- `android-ci.yml` — on push/PR: setup JDK 17 + Android SDK, validate, build,
  upload APK artifact.
- `release.yml` — on tag `v*`: build release APK, create GitHub Release.

Debug CI uses the permanent debug keystore secret `EPUB_APP_KEYSTORE_BASE64`.
The release workflow uses the separate production signing secrets documented
above. Unit tests now run automatically in `android-ci.yml`.
