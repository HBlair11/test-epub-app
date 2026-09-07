# The Livre Magicae — Patch 12 Release Notes

**Version:** 1.12-patch12 (versionCode 12)
**Package:** `com.epubreader.app`
**APK:** `The-Livre-Magicae-Patch-12.apk`
**Offline / privacy-first:** No `INTERNET` permission. All scanning, parsing, and reading happens on-device.

Patch 12 fixes five issues reported after Patch 11: a missing back button on
Author/Series detail views, a grid-layout leak into the Authors/Series lists on
back-navigation, scans stopping when the user navigated away during a scan
(plus an unreliable "Scan Now" button), a duplicate bottom button on the
Folders view, and the reader text crowding the top status bar (no top margin).

---

## 1. Back button now visible on Author/Series detail views

**Symptom:** On an Author's or Series' books layout view, the back arrow that
should return to the Authors/Series list was not visible, so the only way back
was the phone's system back button.

**Root cause:** `applyView()` disabled the drawer indicator
(`drawerToggle.isDrawerIndicatorEnabled = false`) on detail views, which is
correct — but `ActionBarDrawerToggle` draws **no icon on its own** when the
drawer indicator is off. Nothing ever set an "up" indicator drawable, so the
back arrow never rendered.

**Fix:** When entering a detail view, explicitly set the up-indicator to the
existing `ic_arrow_back` drawable and wire its click to `returnToParentList()`:

```kotlin
if (isDetail) {
    drawerToggle.setHomeAsUpIndicator(R.drawable.ic_arrow_back)
    drawerToggle.setToolbarNavigationClickListener { returnToParentList() }
} else {
    drawerToggle.setHomeAsUpIndicator(0)   // clear -> hamburger returns
    drawerToggle.setToolbarNavigationClickListener { binding.drawerRoot.open() }
}
```

**File:** `MainActivity.kt` → `applyView()`

---

## 2. Grid layout no longer leaks into Authors/Series list on back

**Symptom:** After viewing an Author's books in a grid view mode (2/3/4 columns)
and pressing back, the Authors/Series list itself appeared in that grid layout
and was cut off / not visible as a proper list. Only happened when the book
layout view mode was a grid option.

**Root cause:** `GridLayoutManager` extends `LinearLayoutManager`. The row-list
binding checked `layoutManager !is LinearLayoutManager` to decide whether to
force a linear layout — but a leftover `GridLayoutManager` **passed** that check
(it *is* a `LinearLayoutManager`), so it was never replaced and the grid layout
leaked into the Authors/Series list.

**Fix:** Force a plain `LinearLayoutManager` whenever the current layout
manager is a `GridLayoutManager` (or null, or any non-linear manager), in both
the new-adapter and reuse-adapter paths:

```kotlin
val currentLm = binding.recycler.layoutManager
if (currentLm !is LinearLayoutManager || currentLm is GridLayoutManager) {
    binding.recycler.layoutManager = LinearLayoutManager(this)
}
```

**File:** `MainActivity.kt` → `bindRowAdapter()`

---

## 3. Folder scan runs in the background and survives navigation

**Symptom:** When a scan was running and the user navigated to a different
view, the scan appeared to stop — no books loaded, no "scanning complete"
popup — and the user had to stay on the Folders screen for the whole scan.
The "Scan Now" button also sometimes did nothing on tap.

**Root cause:** Two issues:
- **Duplicate concurrent scans.** Nothing prevented launching a second scan
  while one was already running. Two scans on the same importer/DB conflicted
  and the second silently failed — which looked like "Scan Now doesn't work."
- **Spinner visibility.** The `SwipeRefreshLayout` was disabled on non-book
  views (Authors/Series/Folders/Settings), so when the user navigated away
  mid-scan the spinner vanished and the scan *appeared* stopped (the job itself
  was not actually cancelled — only the spinner disappeared).

**Fix:**
- The scan now runs as a **tracked background job** (`scanJob`) in the Activity
  `lifecycleScope`, which is **not** tied to any one shelf view. Navigating
  Library → Settings → Folders (or anywhere else) does **not** cancel it; the
  scan keeps running and books are committed at the end.
- A **duplicate-guard** prevents a second scan from starting while one is
  already running: tapping "Scan Now" again shows "Scan already in progress."
- The refresh layout is now **always enabled**, so the scanning spinner stays
  visible on **every** view until the scan finishes (the scan is no longer
  visually "lost" on navigation). Pull-to-refresh is also available everywhere.
- The scan body is wrapped in `try / catch / finally` so the spinner is
  **always** cleared — even if listing files or committing throws (which would
  otherwise leave the spinner stuck). On failure it shows "Scan failed."
- The first-time folder selection scan (picking a folder) follows the same
  path, so it also survives navigation.

```kotlin
private var scanJob: Job? = null

private fun scanFolder(treeUri: Uri, fromRefresh: Boolean) {
    if (scanJob?.isActive == true) {
        Snackbar.make(binding.refresh, R.string.scan_already_running, …).show()
        return
    }
    …
    viewModel.setScanning(true)
    scanJob = lifecycleScope.launch(Dispatchers.IO) {
        try { … commitImports(prepared) …; showScanResult(newCount) }
        catch (_: Exception) { /* "Scan failed" */ }
        finally { viewModel.setScanning(false) }
    }
}
```

**Files:** `MainActivity.kt` → `scanFolder()`, `applyView()` (refresh enabled),
`res/values/strings.xml` (`scan_already_running`, `scan_failed`)

> **Note on app backgrounding:** This fix ensures the scan survives **in-app
> navigation** (the reported case). If you also need scans to survive the app
> being sent to the background / screen lock, that requires a foreground
> service — see "Future updates" below.

---

## 4. Removed duplicate bottom button on the Folders view

**Symptom:** On the Folders view there was a bottom button (FAB) that, on tap,
popped up the same Select / Scan Now / Remove options already shown inline. This
duplicate popup was unwanted recurring behavior.

**Fix:** The FAB is now hidden on the Folders view; only the inline action
buttons (Scan Now / Select Folder / Remove, or just Select Folder when no
folder is chosen) remain. The Library view keeps its "+" import FAB.

**File:** `MainActivity.kt` → `updateFab()`

---

## 5. Reader top margin synced with the bottom margin

**Symptom:** In the EPUB reader, the chapter text appeared too close to the top
status bar. There was a bottom margin (the "Bottom safe margin" toggle reserved
space for the page indicator), but no matching top margin.

**Fix:** The existing "Bottom safe margin" reader toggle now controls **both**
the top and bottom vertical reading margins **symmetrically** (re-labeled "Top &
bottom margin"). When on, 56px of breathing room is reserved above the first
line of every page **and** 56px below the last line (for the page indicator) —
equal top and bottom spacing. The reserved space is painted with the reading
background color only (white in light mode, sepia in sepia mode, black in dark
mode) — the reader content is never drawn into the margin area.

The pagination layout math now reserves both guards:
- `body.height = viewportHeight − TOP_GUARD − GUARD`
- `body.marginTop = TOP_GUARD` (pushes page content below the status bar)
- `html.height = height + TOP_GUARD`

Both the visible reader WebView and the offscreen measurement WebView use the
same generated HTML, so per-chapter page counts stay consistent with the new
margins. Toggling the setting re-renders the current page immediately.

**Files:** `ReaderActivity.kt` → `topGuardPx()`, `paginationJs()` / `apply()`,
`buildChapterHtml()`; `res/values/strings.xml` (`reader_bottom_margin`,
`reader_bottom_margin_summary`)

---

## File-change summary

| File | Change |
| --- | --- |
| `MainActivity.kt` | Fix #1 back arrow (`setHomeAsUpIndicator`); Fix #2 grid-leak guard in `bindRowAdapter`; Fix #3 tracked `scanJob` + duplicate-guard + try/finally + refresh always enabled; Fix #4 hide Folders FAB |
| `ReaderActivity.kt` | Fix #5 `topGuardPx()` + top guard in pagination `apply()` + `buildChapterHtml` |
| `res/values/strings.xml` | `scan_already_running`, `scan_failed`; relabel `reader_bottom_margin` → "Top & bottom margin" |
| `app/build.gradle.kts` | versionCode 12, versionName `1.12-patch12` |

---

## Build & verification status

- **Build:** `./gradlew assembleDebug` — **BUILD SUCCESSFUL**
- **APK:** `The-Livre-Magicae-Patch-12.apk` (~8.0 MB)
  - `application-label: 'The Livre Magicae'`
  - `versionCode='12'`, `versionName='1.12-patch12'`
  - No `INTERNET` permission (offline / privacy-first)
- **Unit tests:** 18 / 20 pass. The 2 failures
  (`EpubParserTest.parsesRealEpub`, `EpubParserTest.searchesRealEpub`) are
  **pre-existing** — they require a `sample.epub` fixture that is not present
  in the build sandbox and are unrelated to any Patch 12 change.

## Build steps (reproduce)

```bash
cd patch11_src
export JAVA_HOME=/home/user/workspace/tools/jdk-17.0.15+6
export ANDROID_HOME=/home/user/workspace/tools/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug --no-daemon --console=plain
# APK -> app/build/outputs/apk/debug/the-livre-magicae.apk
```

Verify:
```bash
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging \
  app/build/outputs/apk/debug/the-livre-magicae.apk \
  | grep -E "application-label:|versionCode|versionName|package:|uses-permission"
```

## Install

```bash
adb install -r The-Livre-Magicae-Patch-12.apk
```
(The package `com.epubreader.app` stays the same; installing over Patch 11
preserves your library and settings.)
