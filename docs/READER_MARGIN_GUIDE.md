# Reader Margin & Background-Scan Guide (Patch 12)

This guide explains the two Patch 12 subsystems most likely to need future
tweaks — the reader top/bottom margin and the background folder scan — and
exactly which values to change.

---

## A. Reader top & bottom margin

### What it does
A single reader setting, **"Top & bottom margin"** (the toggle that was
previously labeled "Bottom safe margin"), reserves vertical breathing room
above and below the page content so chapter text never crowds the top status
bar and never overlaps the bottom page indicator. When **on**, 56px is reserved
at the top **and** 56px at the bottom of every page (symmetric). When **off**,
no extra vertical margin is reserved. The reserved space is painted with the
reading background color only (white / sepia / black) — reader content is never
drawn into it.

### Where the value lives
The 56px value is hard-coded in **two** places in `ReaderActivity.kt` — keep
them equal so top and bottom stay symmetric:

```kotlin
private fun bottomGuardPx(): Int = if (prefs.pageBottomMargin) 56 else 0
private fun topGuardPx():    Int = if (prefs.pageBottomMargin) 56 else 0
```

To change the margin size (e.g. to 72px), edit **both** `56` values and rebuild.

### How it is applied (the layout math)
In `paginationJs()` → JS `apply()`:

```js
var height = Math.max(40, viewportHeight - TOP_GUARD - GUARD);
html.style.setProperty('height', (height + TOP_GUARD) + 'px', 'important');
body.style.setProperty('margin-top', TOP_GUARD + 'px', 'important'); // overrides body{margin:0 !important}
body.style.setProperty('height', height + 'px', 'important');
```

- `html` spans `0 … viewportHeight − GUARD` (the top `TOP_GUARD` of it is empty
  background; the body sits below it).
- `body` spans `TOP_GUARD … viewportHeight − GUARD` — i.e. page content renders
  from `TOP_GUARD` down to `viewportHeight − GUARD`.
- The `!important` on `margin-top` is required because the reader CSS sets
  `body { margin:0 !important }`; the inline JS value wins because inline styles
  outrank stylesheet rules at equal importance.

### Two WebViews, one layout
Both the **visible** reader WebView and the **offscreen measurement** WebView
(the one that counts pages per chapter) build their HTML through
`buildChapterHtml()`, which calls `paginationJs(bottomGuardPx(), topGuardPx())`
for both. So page counts stay consistent with the new margins automatically —
you do not need to touch the measurement WebView separately.

### Re-rendering on toggle
The toggle is wired in `ReaderSettingsSheet.setupBottomMargin()` → calls
`onApply()` → `ReaderActivity.applySettingsAndReload()`, which re-applies the
theme, cancels in-flight page measurement, resets per-chapter page counts, and
reloads the current chapter. So toggling the margin re-renders the open page
immediately.

### Strings
- `reader_bottom_margin` → "Top & bottom margin"
- `reader_bottom_margin_summary` → "Keep page content clear of the top status
  bar and bottom nav bar (equal top & bottom spacing)"

---

## B. Background folder scan

### What it does
A folder scan lists every `.epub` in the selected SAF folder, matches each
against an in-memory fingerprint map (size + mtime) to skip unchanged books,
prepares (copy + parse + cover) the new/changed ones, and commits them in one
DB transaction so the library refreshes once at the end.

### How it survives navigation (Patch 12)
The scan runs as a **tracked job** in the Activity `lifecycleScope`:

```kotlin
private var scanJob: Job? = null

scanJob = lifecycleScope.launch(Dispatchers.IO) {
    try { … ; showScanResult(newCount) }
    catch (_: Exception) { /* "Scan failed" snackbar */ }
    finally { viewModel.setScanning(false) }
}
```

`lifecycleScope` is tied to the **Activity** lifecycle, not to any one shelf
view, so navigating Library → Settings → Folders (or anywhere else) does **not**
cancel the scan. The spinner (`SwipeRefreshLayout.isRefreshing`) is bound to
`viewModel.scanning`, and the refresh layout is now **always enabled**, so the
spinner stays visible on every view until the scan finishes.

### Duplicate guard
Tapping "Scan Now" (or pull-to-refresh, or selecting a folder) while a scan is
already running shows **"Scan already in progress"** instead of starting a
second concurrent scan that would conflict on the importer/DB.

### If you need scans to survive app backgrounding
This Patch 12 fix covers **in-app navigation** (the reported case). The scan
job lives in `lifecycleScope`, which is cancelled when the Activity is
destroyed. If you later want a scan to keep running after the user presses
Home / locks the screen (i.e. the app is backgrounded but not destroyed), you
need a **foreground service** that owns the scan coroutine:

1. Create a `ScanService : Service` (or `JobIntentService`).
2. Start the scan coroutine in the service's own scope (not `lifecycleScope`),
   showing a persistent notification while scanning.
3. Have the service post scanning state / results back to the UI (e.g. via a
   shared `StateFlow` in the ViewModel or a `LiveData`/broadcast).
4. The UI then only observes state — it does not own the job.

This is a larger change and is only needed if background-during-lock scanning
becomes a requirement.

### Strings
- `scan_already_running` → "Scan already in progress"
- `scan_failed` → "Scan failed. Please try again."

---

## C. Back arrow / grid-leak quick reference

- **Back arrow on detail views** is the `setHomeAsUpIndicator(R.drawable.ic_arrow_back)`
  call in `MainActivity.applyView()`. To change the icon, swap the drawable.
  Passing `0` clears it (returns to the hamburger) and is safe — it does not
  call `Resources.getDrawable(0)`.
- **Grid leak fix** is the `currentLm !is LinearLayoutManager || currentLm is
  GridLayoutManager` guard in `bindRowAdapter()`. `GridLayoutManager extends
  LinearLayoutManager`, so always check for the more specific `GridLayoutManager`
  type when deciding whether to force a linear layout.
