# The Livre Magicae — Offline EPUB Reader for Android


A privacy-first, fully offline EPUB reader with the warmth of a physical bookshelf. 
Featuring a bespoke pagination engine that delivers fast, precise page-by-page reading — no accounts, no cloud, entirely yours.


- **No internet permission.** Books, covers, search, and reading all work offline.
- **No accounts, no analytics, no network calls** of any kind.
- 100% Kotlin, Material Design, Room database, View binding.


---


## Features


### Library / Bookshelf
- **Navigation drawer** (hamburger, top-left, ~4/5 screen width): Currently Reading,
  Library, Favorites, (Finished Reading, To Be Read) -> Removed for now, Authors, Series, (Collections) -> TBD,
  Folders, Settings.
- **Grid & List views** with selectable column count (2 / 3 / 4) for the grid.
- **Sorting**: Recently Added, Title, Series, Author — each with Ascending /
  Descending. The list refreshes and scrolls to the top on change.
- **Search** opens a dedicated full-screen search page with live results.
- **Book details** screen: large hero cover, metadata, real source filename,
  Read / Remove / Remove-from-Reading / Favorite actions.
- Import from a folder (SAF tree) or a single file.


### Reader
- **Paginated reading** (CSS multi-column pagination) — clean page breaks with no
  overlap, like Kindle / ReadEra. Tap left third = previous page, right third =
  next page, center = toggle chrome.
- Reader themes (Light / Sepia / Dark), font family (Serif / Sans / Mono),
  font size (24–56), line height, margins, text alignment (left / justify).
- Table of contents, bookmarks (with snippet + chapter), in-book search.
- Reading progress saved per page and restored on reopen.
- Respects status bar & navigation bar insets (edge-to-edge safe on Android 14+).


---


## Tech Stack & Versions


| Component               | Version        |
|----------------------   |----------------|
| Android Gradle Plugin   | 8.7.2          |
| Kotlin                  | 2.0.20         |
| KSP                     | 2.0.20-1.0.25  |
| Gradle                  | 8.9            |
| JDK                     | 17             |
| compileSdk / targetSdk  | 35             |
| minSdk                  | 24             |


Key libraries: Material Components 1.12.0, AppCompat 1.7.0, Room 2.6.1 (KSP),
Glide 4.16.0, Coroutines 1.8.1, DocumentFile 1.0.1, lifecycle-livedata-ktx 2.8.6.


See [`docs/UPGRADE_GUIDE.md`](docs/UPGRADE_GUIDE.md) for how to bump any of these.


---


## Project Structure


```
EpubReader/
├── app/
│   ├── build.gradle.kts              # module config, deps, SDK levels
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml  # activities, no INTERNET permission
│       │   ├── java/com/epubreader/app/
│       │   │   ├── MainActivity.kt           # Bookshelf + nav drawer
│       │   │   ├── ReaderActivity.kt         # Paginated WebView reader
│       │   │   ├── BookDetailsActivity.kt    # Full-screen book details
│       │   │   ├── SearchActivity.kt         # Dedicated search screen
│       │   │   ├── EpubApp.kt                # Application class
│       │   │   ├── data/                     # Room: entities, DAOs, db, repo, prefs
│       │   │   ├── epub/                     # EPUB engine: parser, importer, resolver, search, covers
│       │   │   └── ui/                       # Adapters, ViewModel, settings sheet, drawer
│       │   │   └── util/                     # Device related settings (keep screen on longer)
│       │   └── res/                          # layouts, drawables, themes, colors, strings
│       └── test/.../epub/EpubParserTest.kt   # Parser unit test (runs against a real EPUB)
├── gradle/wrapper/                           # Gradle wrapper jar + props
├── build.gradle.kts                          # root plugin versions
├── settings.gradle.kts                       # repositories + module include
├── gradle.properties                         # JVM/AndroidX flags
├── gradlew / gradlew.bat                     # wrapper scripts
├── docs/                                     # ← all documentation lives here
│   ├── THEME_COLORS.md
│   ├── CUSTOMIZATION_GUIDE.md
│   ├── BUILD_AND_VALIDATION.md
│   └── UPGRADE_GUIDE.md
├── scripts/                                  # ← helper scripts
│   ├── build.sh
│   ├── validate.sh
│   ├── check_syntax.sh
│   └── release.sh
└── .github/workflows/                        # ← GitHub Actions CI/CD
    ├── android-ci.yml                        # build + validate on every push/PR
    └── release.yml                           # build release APK + GitHub Release on tag
```


---


## Quick Start (local build)


> You do **not** need Android Studio. The gradle wrapper + a JDK 17 + the Android
> SDK command-line tools are enough.


1. Install JDK 17 and Android SDK (command-line tools), accepting licenses:
   ```bash
   export JAVA_HOME=/path/to/jdk-17
   export ANDROID_HOME=/path/to/android-sdk
   $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" \
       "platforms;android-35" "build-tools;35.0.0"
   ```
2. From the project root:
   ```bash
   ./scripts/build.sh        # debug APK
   ```
3. The APK is at `app/build/outputs/apk/debug/the-livre-magicae.apk`.
4. Install on a device:
   ```bash
   adb install app/build/outputs/apk/debug/the-livre-magicae.apk
   ```


See [`docs/BUILD_AND_VALIDATION.md`](docs/BUILD_AND_VALIDATION.md) for the full
validation pipeline (syntax checks, unit tests, APK inspection, release build).


---


## CI/CD (GitHub Actions)


Two workflows are provided in `.github/workflows/`:


- **`android-ci.yml`** — runs on every push and pull request. Sets up JDK 17 +
  Android SDK, runs Kotlin/resource checks, JVM unit tests, then builds the debug APK
  and uploads it as an artifact.
- **`release.yml`** — runs when you push a tag like `v1.1`. Builds a production-signed
  release APK and publishes a GitHub Release with the APK attached.


No secrets are required for the debug/CI build. The release build signs with the
debug key by default (see `docs/BUILD_AND_VALIDATION.md` to set up real signing).


---


## Documentation Index


|                                 Doc                           |                               Purpose                            |
|---------------------------------------------------------------|------------------------------------------------------------------|
| [docs/THEME_COLORS.md](docs/THEME_COLORS.md)                  | The complete color palette, where each color is defined, and     |----------------------------------------------------------------| how change one color everywhere                                  |
| [docs/CUSTOMIZATION_GUIDE.md](docs/CUSTOMIZATION_GUIDE.md)    | Exact file paths + steps to change any feature, EPUB behavior,   | ----------------------------------------------------------------| or layout                                                        |
| [docs/BUILD_AND_VALIDATION.md](docs/BUILD_AND_VALIDATION.md)  | Syntax checks, unit tests, APK build & inspection, release       | ----------------------------------------------------------------| workflow                                                         |
| [docs/UPGRADE_GUIDE.md](docs/UPGRADE_GUIDE.md)                | How to bump Android/Java/Kotlin/Gradle/library versions safely   |


---


## Privacy


The Livre Magicae requests **no network permissions**. You can verify at any time:


```bash
aapt2 dump permissions app/build/outputs/apk/debug/the-livre-magicae.apk
# Expected: only the DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION (a default
# AndroidX permission, not network access). No INTERNET, no ACCESS_NETWORK_STATE.
```
