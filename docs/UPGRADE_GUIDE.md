# Upgrade Guide

How to bump the toolchain and library versions safely. All version numbers live in
two files: the root `build.gradle.kts` (plugin versions) and `app/build.gradle.kts`
(SDK levels + dependencies). The Gradle version lives in
`gradle/wrapper/gradle-wrapper.properties`.

> Always bump one thing at a time, then run `./scripts/validate.sh`. The
> compatibility matrix below is what is known to work together.

---

## Current known-good stack

| Component                  | Version        | Where                                          |
|----------------------------|----------------|------------------------------------------------|
| Android Gradle Plugin (AGP)| 8.7.2          | `build.gradle.kts` (root, `apply false`)       |
| Kotlin                     | 2.0.20         | `build.gradle.kts` (root, `apply false`)       |
| KSP                        | 2.0.20-1.0.25  | `build.gradle.kts` (root, `apply false`)       |
| Gradle (wrapper)           | 8.9            | `gradle/wrapper/gradle-wrapper.properties`      |
| JDK                         | 17             | runtime / CI                                   |
| compileSdk / targetSdk     | 35             | `app/build.gradle.kts`                         |
| minSdk                      | 24             | `app/build.gradle.kts`                         |

### Compatibility rules (the important part)

- **KSP version must match the Kotlin version.** Format: `kotlinVersion-1.0.x`.
  e.g. Kotlin 2.0.20 → KSP `2.0.20-1.0.25`. Find the matching KSP at
  https://github.com/google/ksp/releases.
- **AGP must be compatible with Gradle.** AGP 8.7 needs Gradle 8.9. Check the
  AGP↔Gradle↔JDK table at https://developer.android.com/studio/releases/gradle-plugin.
- **AGP must be compatible with the JDK.** AGP 8.x requires JDK 17. Do **not**
  build with JDK 21 unless AGP explicitly supports it for your AGP version.
- **compileSdk >= targetSdk**, and both should match the AGP-supported SDK.
- **Room KSP + compiler version** must equal the Room runtime version
  (`androidx.room:room-*:2.6.1` here).

---

## Bump Gradle (wrapper)

```bash
# e.g. upgrade to Gradle 8.10
./gradlew wrapper --gradle-version 8.10 --distribution-type bin
```
This rewrites `gradle/wrapper/gradle-wrapper.properties`. Commit the new
`gradle-wrapper.properties` (and the `gradle-wrapper.jar` if it changed).
Verify AGP supports the new Gradle version first.

## Bump AGP / Kotlin / KSP (root build.gradle.kts)

Edit `build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.8.0" apply false       // AGP
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false    // Kotlin
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false  // KSP (match Kotlin!)
}
```
Then update `app/build.gradle.kts` `compileOptions`/`kotlinOptions` if the JDK
target changed, and run `./scripts/validate.sh`.

## Bump compileSdk / targetSdk

In `app/build.gradle.kts`:
```kotlin
compileSdk = 36
targetSdk = 36
```
Raising `targetSdk` can enforce new platform behaviors (e.g. edge-to-edge became
mandatory at targetSdk 35). After a targetSdk bump:
- Re-test all screens for system-bar inset issues (see
  CUSTOMIZATION_GUIDE.md → System bars).
- Re-test the reader's paginated layout (the WebView `innerHeight` must still
  exclude the nav bar).

## Bump minSdk

Lowering `minSdk` widens device support but may require backporting APIs. Raising
it drops old devices. Edit `minSdk` in `app/build.gradle.kts` and re-run tests.

## Bump a dependency

In `app/build.gradle.kts` `dependencies { }`. Keep related versions in sync:

| Dependency group         | Keep in sync                                  |
|--------------------------|-----------------------------------------------|
| Room runtime + ksp + compiler | all `2.x.y` identical                     |
| Glide runtime + compiler     | both `4.x.y` identical                    |
| lifecycle-*                  | all `2.x.y` identical (`runtime`, `livedata-ktx`, `viewmodel-ktx`) |

Find latest versions at https://developer.android.com/jetpack/androidx/versions
and on Maven Central. After editing, run `./scripts/validate.sh`.

## Bump JDK

Install the new JDK (e.g. 21), set `JAVA_HOME`, and ensure AGP supports it. Update
the CI workflow `setup-java` `java-version` and the local docs to match.

---

## After any upgrade

1. `./gradlew clean --console=plain --no-daemon`
2. `./scripts/validate.sh` (compile + unit test + APK + permission check)
3. Install on a device and smoke-test: import a book, open it, page-turn,
   toggle reader settings, open the drawer, search, view details.
