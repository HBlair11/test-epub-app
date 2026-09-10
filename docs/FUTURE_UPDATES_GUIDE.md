# Caesura — Future Updates Guide (Patch 10+)

How to build, modify, and ship the next patch yourself (Patch 11+). This is a
practical manual for the project as it stands after **Patch 10**.

---

## 0. Project layout & build environment

```
/home/user/workspace/patch10/
├── app/
│   ├── build.gradle.kts              # versionCode / versionName here
│   ├── src/main/
│   │   ├── AndroidManifest.xml       # activity -> theme mapping
│   │   ├── java/com/epubreader/app/
│   │   │   ├── MainActivity.kt         # bookshelf / library / drawer
│   │   │   ├── ReaderActivity.kt      # EPUB reader (CSS, pagination, bars)
│   │   │   ├── SearchActivity.kt       # in-app search
│   │   │   ├── data/                  # DB, DAOs, PrefsManager
│   │   │   ├── epub/                  # EpubImporter, EpubResourceResolver, EpubParser
│   │   │   └── ui/                     # BookAdapter, RowAdapter, BookshelfViewModel
│   │   └── res/
│   │       ├── layout/                # activity_main.xml, activity_reader.xml, ...
│   │       ├── drawable/              # reader_search_input_bg.xml (NEW p10), cover_frame, ...
│   │       ├── values/colors.xml      # palette + reader_chrome_* + black/white
│   │       └── values/themes.xml      # Theme.EpubReader, .Reader, .Reader.Sheet
│   └── build/outputs/apk/debug/app-debug.apk   # <-- the built APK
├── docs/                              # PATCH9_NOTES.md, PATCH10_NOTES.md, this file
└── local.properties                   # sdk.dir = /home/user/workspace/tools/android-sdk
```

### Build environment (already set up in this workspace)
- **JDK 17:** `/home/user/workspace/tools/jdk-17.0.20.1+1`
- **Android SDK:** `/home/user/workspace/tools/android-sdk`
  (platform-tools, platforms;android-35, build-tools;35.0.0)
- `local.properties` already points `sdk.dir` at it.

### Build / test commands
```bash
cd /home/user/workspace/patch10
export JAVA_HOME=/home/user/workspace/tools/jdk-17.0.20.1+1
export ANDROID_HOME=/home/user/workspace/tools/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"

# Build the debug APK (output: app/build/outputs/apk/debug/app-debug.apk)
./gradlew assembleDebug --no-daemon --console=plain

# Run unit tests (expect 18/20 pass; 2 pre-existing fixture failures — see below)
./gradlew testDebugUnitTest --no-daemon --console=plain
```

### Copying the APK out + packaging the source zip
```bash
cp app/build/outputs/apk/debug/app-debug.apk /home/user/workspace/app-debug-patch10.apk

cd /home/user/workspace && rm -f Patch-10-source-code.zip
cd patch10 && zip -r /home/user/workspace/Patch-10-source-code.zip . \
  -x "*/build/*" -x "*/.gradle/*" -x "*.DS_Store"
```
Then share `app-debug-patch10.apk` and `Patch-10-source-code.zip` (use the
share tool / drag into the chat).

---

## 1. The 2 "failing" unit tests are not your fault

`EpubParserTest.searchesRealEpub` and `parsesRealEpub` fail with
`NoSuchFileException` because the test fixture EPUB they load is not checked
into the repo. This has been the case since Patch 8. Ignore them unless you
actually add a fixture file. Everything else must pass.

---

## 2. Bumping the version for a new patch

In `app/build.gradle.kts`:
```kotlin
defaultConfig {
    applicationId = "com.epubreader.app"
    minSdk = 24
    targetSdk = 35
    versionCode = 11                       // bump per patch
    versionName = "1.11-patch11"           // bump per patch
    vectorDrawables { useSupportLibrary = true }
}
```
`versionCode` must increase monotonically so Android treats it as an upgrade.

---

## 3. Color & theme system (where the "look" lives)

- **Palette** (`res/values/colors.xml`): `palette_mint #E0F0EA`,
  `palette_slate #95ADBE`, `palette_purple #574F7D`, `palette_plum #503A65`,
  `palette_eggplant #3C2A4D`.
- **Reader chrome** (static, never changes with reading theme):
  `reader_chrome_bg = eggplant`, `reader_chrome_surface = plum`,
  `reader_chrome_text = white`, `reader_chrome_accent = mint`,
  `reader_chrome_text_muted`.
- **Reader content** (the book page): light=white/black, sepia=#F1E3D3/#5A4650,
  dark=black/white.
- **Themes** (`themes.xml`):
  - `Theme.EpubReader` — main app / search / details / splash.
  - `Theme.EpubReader.Reader` — the reader activity (parent: `Theme.EpubReader`).
  - `Theme.EpubReader.Reader.Sheet` — the reader settings bottom sheet
    (parent: `Theme.MaterialComponents.DayNight.BottomSheetDialog` — does **not**
    inherit from `.Reader`, so it must be styled independently, as Patch 10
    did for the black bars).

### Always-black system bars (Patch 10)
All three themes pin the bars to black (`statusBarColor`/`navigationBarColor` =
`@color/black`, `windowLightStatusBar`/`windowLightNavigationBar` = false,
`enforceStatusBarContrast`/`enforceNavigationBarContrast` = false). To change
the bar color app-wide in a future patch, edit **all three** theme blocks, plus
the programmatic calls in `ReaderActivity.applyWindowTheme()`
(`window.statusBarColor` / `window.navigationBarColor` /
`window.decorView.setBackgroundColor` / `binding.root.setBackgroundColor`). The
reader's root padding (from the `OnApplyWindowInsetsListener`) is the single
source of truth for the content margins — **do not** also pad the top/bottom
bars or you will double-inset.

---

## 4. Reader CSS / dark theme / fonts (`ReaderActivity.kt`)

### buildReaderCss()
Builds the `<style>...</style>` injected into each chapter's HTML. Controls
font family, size, line height, margins, alignment, hyphens, image sizing.
Returns the CSS string; the page-counting measurement WebView uses the same
CSS so its page count matches the visible reader.

### Dark-theme text override (Patch 10)
`darkTextOverride(theme, ink)` returns an extra `<style>body, body * { color:
ink !important; }</style>` appended **after** the main style block, **only** in
DARK mode. This forces the light ink color onto every element (including
inline-styled dark text). If a future EPUB uses inline `style="color:#...
!important"`, this CSS won't catch it — then add a JS recolor pass (below).

### Adding a JS-based dark recolor (only if needed later)
If you hit inline `!important` dark text that the CSS override can't reach,
inject a small script after page load. In the `onPageFinished` for
`binding.webView` (around line 229), after the existing pagination JS, add:
```kotlin
binding.webView.evaluateJavascript(
  "(function(){document.querySelectorAll('[style]').forEach(function(el){" +
  "if(el.style.color){el.style.color='#FFFFFF';}" +
  "});})();", null)
```
Run the SAME script on `binding.measureWebView.onPageFinished` too, so the
measured page count stays in sync with the recolored content. This is a
luminance-blind brute-force recolor; only use it if the CSS override proves
insufficient for a real book, because it can affect colored callout boxes.

### Reader search input
The reader's in-book search `EditText` is in `activity_reader.xml` (id
`searchEdit`) and uses `@drawable/reader_search_input_bg` (dark plum + mint
stroke, 10dp corners) with white text. `bottom_sheet_search.xml` is dead/
unused — don't touch it unless you wire it up.

---

## 5. Library / shelf scrolling (`MainActivity.kt`)

### The hard reset path (Patch 10)
`bindBookAdapter`, `reconfigureAdapter`, and `bindRowAdapter` each take a
"reset to top" path when `scrollToTopOnNextContent` is true (or always, for
`reconfigureAdapter`). The recipe (copy this shape for any new book-list
view):
1. `recycler.stopScroll()`
2. save `itemAnimator`, set it `null`
3. fresh adapter with `stateRestorationPolicy = PREVENT_WHEN_EMPTY`
4. fresh LayoutManager
5. `lm.scrollToPositionWithOffset(0, 0)` before submit
6. `submitList(list) { recycler.post { scrollToPositionWithOffset(0,0);
   itemAnimator = saved } }`

This is what makes view switches land cleanly at the top with no visible
scroll motion. If you add a new shelf view or a new book-list screen, route its
content binding through the same `bindBookAdapter` reset path and set
`scrollToTopOnNextContent = true` wherever the user navigates *into* it.

### Where the flag is set
`selectDrawer`, `showSortDirectionDialog`, the rescan Snackbar "Show" action,
author/series row click → `openDetail`, and back/home from a detail view →
`clearDetail`. `viewModel.setView()` always emits (even for the same view), so
the flag is always consumed on the same switch — no leakage.

---

## 6. Import / rescan (`EpubImporter.kt`, `MainActivity.kt`)
- `listEpubFiles(root)` — bulk cursor listing of `.epub` files in a folder tree.
- `sourceFingerprintMap(existing)` — maps a source (filename+size+mtime) to all
  existing DB rows with the same fingerprint; any-match skips re-import.
- `prepareImport(...) / commitImports(...)` — two-phase: build all
  `BookEntity`/cover rows in memory, then a single transactional DB write.
- `MainActivity.scanFolder(...)` — orchestrates the above, shows a bottom
  Snackbar with a "Show" action on `binding.refresh`.

If you add a new metadata field (e.g. publisher, ISBN), extend `BookEntity` +
the Room `@Insert`/`@Update` DAO, bump the DB `version` in `AppDatabase` with a
`Migration` (or `fallbackToDestructiveMigration` for dev), and add the parser
step in `EpubParser`.

---

## 7. EPUB internals (`epub/`)
- `EpubResourceResolver` — serves chapter HTML, images, fonts, and CSS from the
  `.epub` zip via a virtual `https://caesura.local/<bookId>/<entry>` URL. Entry
  paths are `Uri.decode`d, with a decoded→raw fallback (Patch 9) so
  percent-encoded paths in OPF manifests resolve.
- `EpubParser` — reads `META-INF/container.xml` → OPF → spine + manifest + TOC.
- `EpubImporter` — copies the file, parses, writes DB rows, extracts cover.
- `EpubSearchEngine` — full-text search across spine chapters.

The reader WebView loads chapters via
`binding.webView.loadDataWithBaseURL("https://caesura.local/$bookId/", html,
"text/html", "UTF-8", null)`. To add a new content type, add a MIME mapping in
`EpubResourceResolver` and serve it through the same virtual host.

---

## 8. Releasing a patch — checklist
1. Copy the previous patch source to a new working dir:
   `cp -r /home/user/workspace/patch<N-1> /home/user/workspace/patch<N>` (or
   extract the previous source zip if starting fresh). Clean transient dirs:
   `rm -rf the-livre-magicae/.git the-livre-magicae/.gradle the-livre-magicae/.kotlin`.
2. Bump `versionCode` + `versionName` in `app/build.gradle.kts`.
3. `./gradlew assembleDebug --no-daemon --console=plain` → BUILD SUCCESSFUL.
   For a genuine from-scratch recompile proving your edits compile:
   `./gradlew clean assembleDebug --no-daemon --console=plain --no-build-cache --rerun-tasks`.
4. Verify the APK per `docs/LOCAL_BUILD_GUIDE.md` (version, no INTERNET permission,
   zip integrity, new classes present in dex).
5. `cp app/build/outputs/apk/debug/the-livre-magicae.apk
   /home/user/workspace/the-livre-magicae-patch<N>.apk`.
6. Zip the source (exclude `build/`, `.gradle/`, `.kotlin/`, `.git/`, `local.properties`):
   `cd /home/user/workspace/patch<N> && zip -r the-livre-magicae-patch-<N>.zip the-livre-magicae
   -x "*/.git/*" "*/.gradle/*" "*/.kotlin/*" "*/build/*" "*/local.properties"`.
7. Write `docs/PATCH<N>_NOTES.md` (mirror the PATCH16_NOTES structure: what
   changed, root cause, fix, files table, build/test status).
8. Share the APK, the source zip, and the notes doc with the user.

---

## 9. Patch 10 → Patch 11 quick-start
If the next request is "Patch 11: ...", the fastest path is:
```bash
cp -r /home/user/workspace/patch10 /home/user/workspace/patch11
cd /home/user/workspace/patch11
# bump versionCode/versionName, make edits, then:
export JAVA_HOME=/home/user/workspace/tools/jdk-17.0.20.1+1
export ANDROID_HOME=/home/user/workspace/tools/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug testDebugUnitTest --no-daemon --console=plain
```
Then follow the release checklist above.

---

## Master product roadmap

See `docs/FUTURE_UPDATES_ROADMAP_AND_PRODUCT_GUIDE.md` for the complete product evaluation, 71-point improvement list, search roadmap, Reading Nook concept, engineering rules, and release sequencing.
