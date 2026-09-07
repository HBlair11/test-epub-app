# Patch 9 — Release Notes

**Version:** `1.9-patch9` (versionCode `9`)
**Build:** `app-debug-patch9.apk`
**Base:** Patch 8 source. This patch reverts four Patch 8 behaviors back to the
Patch 7 reading experience, extends the crossfade transition to every page turn,
makes folder re-scans fast and non-disruptive, and fixes the Library scroll bug.

> Patch 7 source was not available, so these are **behavioral reverts** — each
> change restores the user-visible behavior you described from Patch 7, rebuilt
> on top of the Patch 8 codebase. The unused database column from Patch 8 is
> left in place (no schema downgrade), so existing libraries are not touched.

---

## 1. Instant, stable total page count — reverted to live measurement

**Patch 8** computed a synthetic page map once on import (ADE byte-mapping,
~1024 bytes/page) and cached it in the `page_map_csv` column so the total page
count was instant. The downside: that count was a rough byte estimate that did
**not** reflect the actual number of screen pages at your current font size,
margin, or line spacing — so the seeker and "page X of Y" drifted from reality.

**Patch 9** reverts to the real per-chapter screen-page measurement that was
dormant in Patch 8:

- `ReaderActivity.loadBook()` initializes `chapterPageCounts = IntArray(spine.size) { -1 }`
  and kicks off `startMeasurement()`.
- Each chapter is rendered into an offscreen WebView sized to the real screen +
  current reader settings; `readMeasuredCount()` counts how many screen-pages
  it actually produces. A 4-second `measureWatchdog` falls back to "not ready"
  for any chapter that fails to measure.
- `updatePageIndicator()` shows "page X of Y" live, with "…" while chapters are
  still being measured. Changing font size / margins / line spacing
  (`applySettingsAndReload()`) re-measures, so the count tracks your settings —
  exactly how Calibre and Readium behave.
- Once all chapters are measured, `enablePerPageSeeker()` switches the seeker
  from chapter-level to true per-page granularity.

The reader no longer reads `page_map_csv`. `EpubImporter` no longer computes an
`EpubPageMap` on import (`pageMapCsv = existing?.pageMapCsv`). The column is kept
in the schema (DB still version 4) so existing installs are not migrated.

**Files:** `ReaderActivity.kt` (loadBook, updatePageIndicator,
applySettingsAndReload, measurement pipeline), `EpubImporter.kt` (importDocument,
prepareFromWorking).

---

## 2. Publisher (embedded) font — now actually loads

**Patch 8** offered a "Publisher" font option, but embedded `@font-face` fonts
inside the EPUB were not rendering — you saw no difference versus Readium /
Calibre for the same book.

**Root cause:** the book's CSS references font files by their entry path inside
the EPUB (e.g. `Fonts/My Book Serif.otf`). When the entry path contained a space
or other reserved character, it was percent-encoded in the resource URL
(`Fonts/My%20Book%20Serif.otf`), but `EpubResourceResolver` looked up the
**encoded** string in the EPUB entry table — which stores the **decoded** name.
The lookup failed, the font file 404'd, and the reader silently fell back to the
system font.

**Fix:** `EpubResourceResolver.entryPathFor()` now `Uri.decode()`s the entry path
before resolving it against the EPUB's entry table, so embedded font files with
spaces (and other percent-encoded characters) load correctly. In Publisher mode
the reader already emits **no** `font-family` override, so the book's own CSS
`@font-face` rules take effect — matching Calibre / Readium.

**File:** `epub/EpubResourceResolver.kt` (added `import android.net.Uri`,
`Uri.decode` in `entryPathFor`).

---

## 3. Calibre / Readium-style image handling

**Patch 8** injected aggressive image CSS:
`object-fit: contain; max-height: 100%; break-inside: avoid`. For inline icons
and small images this was a regression — the icon was stretched to the full
viewport height and "overpowered" the surrounding text, the same annoyance you
see in ReadEra.

**Patch 9** reverts to the minimal, Patch 7-style image constraint:

```css
img {
  max-width: 100% !important;
  height: auto !important;
}
```

- `max-width: 100%` keeps large images from overflowing the page width.
- `height: auto` preserves the image's natural aspect ratio.
- No `object-fit`, no forced `max-height`, no `break-inside` override — so the
  book's own CSS for images is respected, and inline icons render inline at
  their natural size, just like the Calibre editor and Readium.

**File:** `ReaderActivity.kt` (`buildReaderCss`).

---

## 4. Crossfade transition on every page turn

You loved the snapshot crossfade used for cross-chapter transitions and asked for
the same seamless visual on **all** navigation:

- **In-chapter page turns** (`performPageTurn`): the current page is captured to
  a bitmap snapshot, the WebView jumps instantly to the next page, then the
  snapshot crossfades out over ~220 ms.
- **Cross-chapter turns** (`seekToAbsolutePage` when the spine index changes):
  the old chapter's last page is held as a snapshot while the new chapter
  loads and positions, then crossfades out.
- **TOC / element jumps** (`navigateToUrl`, `gotoElementById`): same capture →
  position → crossfade-out. `gotoElementById` now navigates with
  `behavior: 'instant'` (no scroll animation) so the snapshot fade is the only
  motion you see.
- **Bookmarks** (`goToBookmark`): same crossfade when the bookmark is in a
  different chapter.

`capturePageSnapshot()` recycles the previous snapshot bitmap on each capture to
avoid memory growth on frequent per-turn captures. The snapshot view sits above
the WebView (`binding.snapshotView`) and fades alpha → 0 via
`dismissPageSnapshot()` once the target page is positioned.

**File:** `ReaderActivity.kt` (performPageTurn, seekToAbsolutePage,
navigateToUrl, goToBookmark, gotoElementById, capturePageSnapshot,
dismissPageSnapshot).

---

## 5. Fast, non-disruptive folder re-scan

Re-scans are now quick and quiet, matching the behavior you described from
ReadEra:

**a. Bulk cursor listing.** `EpubImporter.listEpubFiles()` walks the SAF tree
with **one cursor query per directory** that returns `name + size + mtime +
mime_type` for every child in a single pass. This replaces the old per-file
`DocumentFile.name()` / `.length()` / `.lastModified()` calls (each a separate
SAF cursor query) that made scanning 250–300 books take minutes. The first
full scan of a large library still takes time (each new book must be copied,
parsed, and cover-extracted), but every scan after that is fast.

**b. Fingerprint skip.** `BookDao.getSourceFingerprints()` loads every existing
book's `(source_filename, file_size, source_last_modified, cached_path)` into an
in-memory map in **one** DB query. Each scanned file is checked against this map
via `RescanDecision.shouldSkip()` — if the cached EPUB still exists and size +
mtime are unchanged, the file is skipped entirely (no copy, no checksum, no
re-parse).

**c. Batched, transactional commit.** New and changed books are *prepared*
(copied, checksummed, parsed, cover-extracted) without touching the DB, then
**all committed in a single Room transaction** via `commitImports()`. Because
Room's Flow re-emits only at transaction commit, the library list refreshes
**once at the end** instead of once per book — so you no longer see the grid
thrashing and re-sorting while a scan runs.

**d. Bottom Snackbar.** The scan-result Snackbar is now hosted on the main
content view (inside the `CoordinatorLayout`) so it sits at the bottom above the
system nav bar instead of floating in the middle of the layout. When new books
are found it offers **Show**, which switches to the Library and scrolls to the
top (title ascending) so the newly added book is in view. When nothing new is
found it briefly reads "Scan completed, no new books found." The scanning
spinner disappears as soon as the (fast) skip pass completes.

**Files:** `epub/EpubImporter.kt` (ScannedFile, listEpubFiles,
walkChildrenBulk, sourceFingerprintMap, PreparedImport, prepareImport,
prepareFromWorking, commitImport, commitImports), `data/BookDao.kt`
(getSourceFingerprints), `data/SourceFingerprint.kt` (new), `MainActivity.kt`
(scanFolder rewritten, showScanResult, Snackbar hosts).

---

## 6. Library always starts at the top

**Bug:** navigating from **Currently Reading** to **Library** via the drawer
left the Library scrolled to wherever the Currently Reading book was — the top
book of Currently Reading "stayed" at the top of Library and the list was built
around it. You had to scroll up manually to reach the first title-ascending
book. This affected all view modes (grid and list).

**Fix:** `selectDrawer()` now sets `scrollToTopOnNextContent = true` before
switching views. The content observer already honored this flag after the list
was submitted (`scrollToPosition(0)`), but it was only being set on sort changes.
Now every drawer navigation lands the target shelf at its first row. The
"Show" action in the scan Snackbar sets the same flag so new books appear at the
top of the Library.

**File:** `MainActivity.kt` (selectDrawer, showScanResult).

---

## 7. Folders view

The Folders view already lists the currently selected folder and offers
**Rescan now**, **Select another folder**, and **Remove folder**. Patch 9 keeps
this and moves the folder-related Snackbars to the bottom host for consistency.
Single-folder selection remains (one active scan root at a time, persisted
across restarts).

---

## Files changed in Patch 9

| File | Change |
|------|--------|
| `app/src/main/java/com/epubreader/app/ReaderActivity.kt` | Image CSS revert; page-count revert to live measurement; crossfade on all page turns; `gotoElementById` instant nav; snapshot bitmap recycling |
| `app/src/main/java/com/epubreader/app/epub/EpubResourceResolver.kt` | `Uri.decode` entry paths so embedded fonts load |
| `app/src/main/java/com/epubreader/app/epub/EpubImporter.kt` | Bulk `listEpubFiles`; fingerprint map; `prepareImport` / `commitImports` batched transactional import; no `EpubPageMap` on import |
| `app/src/main/java/com/epubreader/app/data/BookDao.kt` | `getSourceFingerprints()` bulk query |
| `app/src/main/java/com/epubreader/app/data/SourceFingerprint.kt` | New lightweight fingerprint data class |
| `app/src/main/java/com/epubreader/app/MainActivity.kt` | `scanFolder` rewrite; `selectDrawer` scroll-to-top; `showScanResult` bottom Snackbar + Show scroll-to-top; Snackbar hosts |
| `app/build.gradle.kts` | versionCode `9`, versionName `1.9-patch9` |
| `local.properties` | `sdk.dir` pointing at the build SDK |

---

## Build & install

```bash
export JAVA_HOME=/home/user/workspace/tools/jdk-17.0.20.1+1
export ANDROID_HOME=/home/user/workspace/tools/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
cd /home/user/workspace/patch9
./gradlew assembleDebug --no-daemon --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK is also provided ready-to-install as `app-debug-patch9.apk`.

## Tests

`./gradlew testDebugUnitTest` → 18 / 20 pass. The 2 failures
(`EpubParserTest.parsesRealEpub`, `searchesRealEpub`) are **pre-existing** — they
reference a test-fixture EPUB that is not present in the source tree. They fail
identically on the unmodified Patch 8 baseline and are unrelated to any Patch 9
change.
