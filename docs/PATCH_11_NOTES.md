# Patch 11 — The Livre Magicae

**App:** The Livre Magicae (package `com.epubreader.app` — unchanged)
**Version:** `1.11-patch11` (versionCode = 11)
**Base:** Patch 10 (`1.10-patch10`)
**APK file:** `the-livre-magicae.apk`

## Build

```bash
cd /home/user/workspace/patch11_src
export JAVA_HOME=/home/user/workspace/tools/jdk-17.0.15+6
export ANDROID_HOME=/home/user/workspace/tools/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug --no-daemon --console=plain
# APK -> app/build/outputs/apk/debug/the-livre-magicae.apk
```

> Note: use the JDK at `/home/user/workspace/tools/jdk-17.0.15+6`. The Patch 10
> PDF mentioned `jdk-17.0.20.1+1`; that path is not present in this environment
> — `jdk-17.0.15+6` is the working JDK 17 for this build.

## What Patch 11 does

1. **App name and APK file renamed.** Display name → "The Livre Magicae",
   APK output → `the-livre-magicae.apk`. Package name unchanged (no reinstall).
2. **Reader font size range 24–56, default 24** on fresh install, remembered
   thereafter per session until changed.
3. **Scroll position restored** on return from the reader, for Library, Authors
   list, Series list, Author detail, Series detail. Currently Reading is
   excluded and always top-resets on return. Drawer view-switching still
   top-resets exactly as in Patch 10.
4. **Back buttons** on Author/Series detail views (toolbar back arrow that
   restores the parent list scroll; hamburger reappears on return).
5. **Folder and Settings views start at the top** (not mid-screen); redundant
   "Settings" heading removed.
6. **Screen On toggle** in Settings — keeps the screen awake for 10 minutes
   longer than the device's screen-off timeout, app-wide (Library, Reader,
   Book Details). See `SETTINGS_GUIDE.md`.
7. **Docs + validation** updated (`README.md`, `scripts/validate.sh`, new
   guides in `docs/`).

## Files changed

| File | Change |
|------|--------|
| `app/build.gradle.kts` | versionCode=11, versionName=1.11-patch11, APK rename |
| `app/src/main/res/values/strings.xml` | app_name + Screen On strings |
| `app/src/main/java/com/epubreader/app/data/PrefsManager.kt` | Font range 24–56, default 24, keepScreenOn pref |
| `app/src/main/res/layout/dialog_reader_settings.xml` | Slider 24–56 |
| `app/src/main/java/com/epubreader/app/ui/ReaderSettingsSheet.kt` | Clamp font to MIN..MAX |
| `app/src/main/java/com/epubreader/app/MainActivity.kt` | Scroll restore, back buttons, top alignment, Screen On wiring |
| `app/src/main/java/com/epubreader/app/ReaderActivity.kt` | Keep-screen-on lifecycle |
| `app/src/main/java/com/epubreader/app/BookDetailsActivity.kt` | Keep-screen-on lifecycle |
| `app/src/main/java/com/epubreader/app/util/KeepScreenOnController.kt` | NEW — keep-screen-on controller |
| `README.md` | Font range docs 10–40 → 24–56 |
| `scripts/validate.sh` | APK path → the-livre-magicae.apk |
| `docs/*.md` | NEW guides |

## Build / test status

- `./gradlew assembleDebug` → **BUILD SUCCESSFUL**. APK is 7.0 MB.
- `aapt2 dump badging` confirms `application-label:'The Livre Magicae'`,
  `versionCode='11'`, `versionName='1.11-patch11'`, no `INTERNET` permission.
- `./gradlew testDebugUnitTest` → 18 / 20 pass. The 2 failures
  (`EpubParserTest.searchesRealEpub`, `EpubParserTest.parsesRealEpub`) are
  pre-existing: they load `/home/user/workspace/sample.epub`, which is not
  checked into the repo. Unrelated to Patch 11.
