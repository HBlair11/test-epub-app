# The Livre Magicae — Developer Guide

> A complete reference for maintaining, customizing, and extending the app.
> All file paths are relative to the project root (`patch11_src/`).
> Current version: **1.12-patch12** (versionCode 12), package `com.epubreader.app`.

The Livre Magicae is a fully-offline Android EPUB reader. No `INTERNET`
permission is declared anywhere — all scanning, parsing, cover extraction, and
reading happen on-device. Library books are copied into app-private storage and
managed through a Room database; reader preferences live in SharedPreferences.

---

## Table of contents

1. [Project layout](#1-project-layout)
2. [Build, version & release process](#2-build-version--release-process)
3. [Q1 — Duplicate handling: manual import vs folder scan](#3-q1--duplicate-handling-manual-import-vs-folder-scan)
4. [Q2 — The Margin slider: where it lives, values, how to change it](#4-q2--the-margin-slider-where-it-lives-values-how-to-change-it)
5. [Q3 — Reader settings sheet UX (stop accidental slider changes)](#5-q3--reader-settings-sheet-ux-stop-accidental-slider-changes)
6. [Q4 — Hamburger drawer: add sections & change width](#6-q4--hamburger-drawer-add-sections--change-width)
7. [App feature & gesture reference](#7-app-feature--gesture-reference)
8. [Settings reference (app-level + reader-level)](#8-settings-reference-app-level--reader-level)
9. [Reader rendering internals (CSS + pagination JS)](#9-reader-rendering-internals-css--pagination-js)
10. [Data layer (Room + preferences)](#10-data-layer-room--preferences)
11. [Themes & colors](#11-themes--colors)
12. [Nuances & gotchas](#12-nuances--gotchas)

---

## 1. Project layout

```
app/src/main/
├── java/com/epubreader/app/
│   ├── MainActivity.kt            # Shelf UI, drawer, scan, folders, settings
│   ├── ReaderActivity.kt          # EPUB reader: WebView pagination, gestures, chrome
│   ├── BookDetailsActivity.kt     # Book details screen
│   ├── SearchActivity.kt          # Library search
│   ├── EpubApp.kt                 # Application class
│   ├── epub/
│   │   ├── EpubImporter.kt        # Folder scan + single import + dedup
│   │   ├── EpubParser.kt          # EPUB parsing (metadata, spine, NCX/nav)
│   │   ├── EpubSearchEngine.kt    # In-book text search
│   │   ├── CoverExtractor.kt      # Cover image extraction
│   │   ├── RescanDecision.kt      # Pure fast-skip logic (unit-testable)
│   │   ├── ReaderPageMapping.kt   # Page mapping helpers
│   │   ├── EpubResourceResolver.kt# Serves EPUB resources to the WebView
│   │   └── EpubModels.kt          # Parsed EPUB data models
│   ├── data/
│   │   ├── AppDatabase.kt         # Room database
│   │   ├── BookDao.kt             # Queries (incl. fingerprint/checksum dedup)
│   │   ├── BookEntity.kt          # Book row
│   │   ├── PrefsManager.kt         # All SharedPreferences (reader + shelf prefs)
│   │   ├── BookmarkDao / BookmarkEntity.kt
│   │   ├── CollectionDao / CollectionEntity.kt
│   │   ├── GroupedRow.kt          # Author/series row model
│   │   └── SourceFingerprint.kt   # Rescan fingerprint DTO
│   ├── ui/
│   │   ├── BookshelfViewModel.kt  # State: view, sort, content, scanning (contains ShelfView)
│   │   ├── BookAdapter.kt         # Library grid/list adapter
│   │   ├── RowAdapter.kt           # Authors/Series row adapter
│   │   ├── DrawerAdapter.kt       # Nav drawer rows (contains DrawerItem)
│   │   ├── ReaderSettingsSheet.kt # Reader settings bottom sheet
│   │   ├── TocAdapter / BookmarkAdapter / SearchResultAdapter / SortOptionAdapter / DrawerAdapter.kt
│   │   └── KeepScreenOnController.kt (in util/)  # FLAG_KEEP_SCREEN_ON + 10-min countdown
│   └── util/KeepScreenOnController.kt
├── res/
│   ├── layout/                    # activity_main, activity_reader, activity_book_details,
│   │                              #   dialog_reader_settings, item_drawer, item_book_grid, ...
│   ├── values/strings.xml         # ALL user-facing strings (single source of truth)
│   ├── values/themes.xml          # Theme.MaterialComponents.DayNight.NoActionBar
│   ├── values/colors.xml          # accent, reader_bg_*, reader_chrome_bg, etc.
│   └── drawable/                  # ic_arrow_back, ic_folder, ic_book, ic_add, ...
└── AndroidManifest.xml            # package, no INTERNET permission
app/build.gradle.kts               # versionCode/versionName, applicationId, SDK, rename to the-livre-magicae.apk
```

**Key principle:** every user-facing string lives in `res/values/strings.xml`.
Never hard-code user-facing text in Kotlin — add a `<string>` and reference it.

---

## 2. Build, version & release process

### Build environment
```bash
export JAVA_HOME=/home/user/workspace/tools/jdk-17.0.15+6      # Temurin JDK 17
export ANDROID_HOME=/home/user/workspace/tools/android-sdk      # platform 35, build-tools 35.0.0
export PATH="$JAVA_HOME/bin:$PATH"
cd patch11_src
./gradlew assembleDebug --no-daemon --console=plain
# APK -> app/build/outputs/apk/debug/the-livre-magicae.apk
```

### Version bump (every patch)
Edit `app/build.gradle.kts`:
```kotlin
versionCode = 13                       // increment every release
versionName = "1.13-patch13"           // human-readable
```
`applicationId` stays `com.epubreader.app` — only the **display label** in
`strings.xml` (`app_name = "The Livre Magicae"`) is the product name. Keeping
the package stable means installing over an older build preserves the user's
library and settings.

### Verify the APK
```bash
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging \
  app/build/outputs/apk/debug/the-livre-magicae.apk \
  | grep -E "application-label:|versionCode|versionName|package:|uses-permission"
```
Expect: `application-label:'The Livre Magicae'`, the new versionCode/Name, and
**no** `INTERNET` permission (the only permission is the auto-generated
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`).

### Unit tests
```bash
./gradlew testDebugUnitTest --no-daemon --console=plain
```
18/20 pass. The 2 failures (`EpubParserTest.parsesRealEpub`,
`searchesRealEpub`) require a `sample.epub` fixture that is not present in the
sandbox — they are pre-existing and unrelated to any patch.

### Release deliverables (per patch)
1. **APK** — copy `the-livre-magicae.apk` to `The-Livre-Magicae-Patch-N.apk`.
2. **Source zip** — `zip -rq The-Livre-Magicae-Patch-N-source.zip patch11_src`
   (exclude `build/`, `.gradle/`, `.idea/`, `local.properties`).
3. **Release notes** — markdown (the `.md` you can export to PDF yourself).
4. **Guides** — any extra `.md` files for new subsystems.

---

## 3. Q1 — Duplicate handling: manual import vs folder scan

> *If a user manually imported some epub files before selecting a folder, and
> the same epub files are present in the selected folder during folder scan,
> will duplicate copies be created or will the app bypass them as already
> existing?*

**No duplicates are ever created.** The app has **two layers** of de-duplication
so the same physical book is never imported twice, and reading progress /
favorites / bookmarks are always preserved.

### Layer 1 — fast skip by filename + size + mtime
When a folder scan runs, the importer loads every already-imported book's
**fingerprint** (source filename, file size, source last-modified time, and the
on-disk path of the cached copy) into an in-memory map with a **single DB
query** (`BookDao.getSourceFingerprints()` → `sourceFingerprintMap()`).

For each file found in the folder, it checks `RescanDecision.shouldSkip(...)`:

```kotlin
// RescanDecision.kt — pure logic, unit-tested
fun shouldSkip(existingFileSize, existingMtime, sourceSize, sourceMtime,
               cachedFileExists): Boolean {
    if (!cachedFileExists) return false        // cached copy gone -> re-import
    if (sourceSize <= 0L) return false         // unknown size -> can't trust
    return existingFileSize == sourceSize && existingMtime == sourceMtime
}
```

A book is skipped **only** when the cached epub still exists **and** size + mtime
match exactly. If they match, the file is treated as already-imported — **no
copy, no checksum, no parse** — so rescans of a large folder finish in seconds.

### Layer 2 — content checksum de-dup (SHA-1)
If the fast-skip does **not** match (different filename, different size, or the
file was modified), the file is fully imported — but the **content checksum**
still prevents duplicates:

```kotlin
// EpubImporter.importFileResult() / prepareFromWorking()
val checksum = checksum(working)                       // SHA-1 of file bytes
val target = File(epubDir, "$checksum.epub")          // stored by checksum
if (!target.exists()) { file.copyTo(target, ...) }     // no dup file on disk
val existing = db.bookDao().getByChecksum(checksum)     // no dup DB row
val entity = BookEntity(id = existing?.id ?: 0, ...)     // reuse id if exists
// -> if existing != null: UPDATE (preserves progress/favorites/bookmarks)
// -> if existing == null: INSERT (new book)
return ImportResult(id, existing == null)               // isNew flag
```

Because the cached file is named `<sha1>.epub`, two imports of the **same
content** always resolve to the **same** file and the **same** DB row. So even
if a manually-imported file and a folder file have different names or mtimes
but identical content, the second pass finds the existing row by checksum and
**updates** it instead of inserting a duplicate.

### How manual import records the fingerprint
The Library "+" FAB → file picker → `importUriResult(uri)` →
`importDocument(uri, sourceName = doc.name, sourceSize, sourceLastModified)`.
It stores `sourceFilename` (= the picked file's name), `fileSize`, and
`sourceLastModified` on the book row. So when the folder scan later sees the
same file (same name + size + mtime), Layer 1 skips it instantly.

### Answer summary
| Scenario | Result |
| --- | --- |
| Folder file == manually-imported file (same name, size, mtime) | **Layer 1 fast-skip** — treated as already existing, zero cost |
| Same content, different name / size / mtime | **Layer 2 checksum dedup** — reuses the cached `<sha1>.epub` file and the existing DB row; progress/favorites preserved; only pays copy+checksum cost |
| Genuinely new book | Imported as new |

### Edge case (documented, acceptable)
The fast-skip is keyed by **source filename**. If two *different* files in
*different subfolders* of the selected folder had the **same filename + size +
mtime** (astronomically unlikely), the second would be skipped. This is the
documented trade-off for single-folder scanning. Layer 2 (checksum) is the
real safety net for content identity.

### Where to change dedup behavior
- **Fast-skip logic:** `RescanDecision.shouldSkip()` (pure, unit-testable).
- **Fingerprint loading:** `EpubImporter.sourceFingerprintMap()` /
  `BookDao.getSourceFingerprints()`.
- **Checksum dedup:** `EpubImporter.importFileResult()` /
  `prepareFromWorking()` / `BookDao.getByChecksum()`.
- To make a re-scan always re-import changed files, you only need to touch
  `shouldSkip()` — everything else flows from it.

---

## 4. Q2 — The Margin slider: where it lives, values, how to change it

> *Where is the Margin range selector configured, what are the values, how is
> this margin used, and how do I update the default and the available range?*

### Important: two different "margin" concepts
The reader has **two** margin controls — don't confuse them:

| Control | What it does | Where |
| --- | --- | --- |
| **"Margin" slider** | **Horizontal** left/right page padding (the gap between text and the screen's left/right edges) | `marginSlider` |
| **"Top & bottom margin" switch** | **Vertical** breathing room above the first line and below the last line of every page (56px), synced top = bottom | `bottomMarginSwitch` / `pageBottomMargin` |

This section is about the **"Margin" slider** (horizontal padding).

### Where it is configured
1. **Preference storage + default:** `PrefsManager.kt`
   ```kotlin
   var margin: Int
       get() = prefs.getInt(KEY_MARGIN, 8)          // default = 8
       set(value) { prefs.edit().putInt(KEY_MARGIN, value).apply() }
   // ...
   const val KEY_MARGIN = "margin"
   ```
2. **Slider UI + range:** `res/layout/dialog_reader_settings.xml`
   ```xml
   <com.google.android.material.slider.Slider
       android:id="@+id/marginSlider"
       android:valueFrom="0"      <!-- min -->
       android:valueTo="48"      <!-- max -->
       android:stepSize="2"      <!-- increments: 0,2,4,...,48 -->
       app:tickVisible="false" />
   ```
3. **Wiring (read/apply):** `ReaderSettingsSheet.kt` → `setupMargin()`
   ```kotlin
   binding.marginSlider.value = prefs.margin.toFloat()
   binding.marginSlider.addOnChangeListener { _, value, fromUser ->
       if (fromUser) { prefs.margin = value.toInt(); onApply() }
   }
   ```
4. **How the value is used (the CSS):** `ReaderActivity.kt` → `buildReaderCss()`
   ```kotlin
   val margin = prefs.margin
   // in the <style> body block:
   //   padding:0 ${margin}px !important;          // left/right padding
   //   column-gap:${2 * margin}px !important;    // gap between paginated columns
   ```
   So `margin` is the horizontal padding in px, and the column gap is
   `2 * margin`. Increasing it widens the side gutters and the gap between
   page columns.

### Values
- **Range:** 0 → 48 (px)
- **Step:** 2 (so 0, 2, 4, …, 48)
- **Default:** 8

### How to change the default
In `PrefsManager.kt`:
```kotlin
get() = prefs.getInt(KEY_MARGIN, 8)   // change this 8 -> e.g. 12
```
Existing installs keep their stored value; only fresh installs (or a value
never set before) get the new default. To force everyone onto a new default you
would need a one-time migration key.

### How to change the available range
In `dialog_reader_settings.xml`, change `android:valueFrom`, `android:valueTo`,
and `android:stepSize`:
```xml
android:valueFrom="0"
android:valueTo="64"     <!-- wider max -->
android:stepSize="4"     <!-- coarser steps -->
```
**Important:** also clamp in `PrefsManager` so an old stored value outside the
new range doesn't crash the Material Slider (it throws if the initial value
is outside `valueFrom..valueTo`). Mirror the font-size pattern:
```kotlin
var margin: Int
    get() = prefs.getInt(KEY_MARGIN, 8).coerceIn(MIN_MARGIN, MAX_MARGIN)
    set(value) { prefs.edit().putInt(KEY_MARGIN, value.coerceIn(MIN_MARGIN, MAX_MARGIN)).apply() }

companion object {
    const val MIN_MARGIN = 0
    const val MAX_MARGIN = 64
}
```
And in `setupMargin()`, clamp before assigning (just like `setupFontSize()`
already does):
```kotlin
val current = prefs.margin.coerceIn(MIN_MARGIN, MAX_MARGIN)
if (current != prefs.margin) prefs.margin = current
binding.marginSlider.value = current.toFloat()
```

> The **other** reader sliders follow the identical pattern and you can
> customize them the same way:
> - **Font size:** `fontSizeSlider` — `valueFrom=24`, `valueTo=56`, `step=1`,
>   default `24` (`MIN_FONT_SIZE`/`MAX_FONT_SIZE` in `PrefsManager`; clamped
>   on read).
> - **Line spacing:** `lineHeightSlider` — `valueFrom=1.0`, `valueTo=2.2`,
>   `step=0.1`, default `1.6`.

### "Top & bottom margin" switch (the vertical one)
- Pref: `prefs.pageBottomMargin: Boolean` (KEY `bottom_guard`, default `true`).
- Value is **hard-coded 56px** in `ReaderActivity.kt` in **two** places — keep
  them equal so top and bottom stay symmetric:
  ```kotlin
  private fun bottomGuardPx(): Int = if (prefs.pageBottomMargin) 56 else 0
  private fun topGuardPx():    Int = if (prefs.pageBottomMargin) 56 else 0
  ```
- To change the vertical margin size, edit **both** `56` values. See
  `READER_MARGIN_GUIDE.md` for the full layout math.

---

## 5. Q3 — Reader settings sheet UX (stop accidental slider changes)

> *While scrolling up to reveal more settings, ranges are accidentally clicked
> if I tap in the slider area to scroll. I want this to be smooth and seamless.*

### Why it happens
The reader settings sheet is a `BottomSheetDialog` whose content is a plain
`ScrollView` containing several `Material Slider`s. Material Slider seeks on
**tap** (it moves the thumb to the touch x) **and** on horizontal drag. When
you scroll vertically by swiping over a slider, any horizontal component or a
stray tap moves the slider. The slider also eagerly captures touches, which
fights the `ScrollView`'s vertical scrolling.

### Fix — three layers, in order of effort/impact

#### A. Use `NestedScrollView` instead of `ScrollView` (easy, helps a lot)
`NestedScrollView` coordinates scrolling with the `BottomSheet` and with
nested touch consumers much better than the legacy `ScrollView`. In
`dialog_reader_settings.xml`:
```xml
<androidx.core.widget.NestedScrollView
    android:id="@+id/settingsScroll"
    ... >
```
No Kotlin change needed (the binding id `settingsScroll` stays the same;
`NestedScrollView` is a drop-in for `ScrollView`).

#### B. Add vertical padding around each slider row (easy, big win)
Give each slider a tall wrapper so the user scrolls by grabbing the **label /
padding area**, not the track. Replace each bare slider block with:
```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingTop="14dp"
    android:paddingBottom="14dp">
    <TextView style="@style/ReaderSettingLabel" android:text="@string/settings_margin" />
    <com.google.android.material.slider.Slider ... />
</LinearLayout>
```
This keeps fingers off the track during casual scrolling.

#### C. Gate seeking to deliberate horizontal drags (eliminates tap-seek)
Material Slider has no built-in "disable tap-to-seek", but you can make it
ignore a touch unless it becomes a real horizontal drag. Add a small helper and
call it for every slider in `ReaderSettingsSheet.init`:

```kotlin
import android.view.MotionEvent

/** Only let the slider seek on a deliberate horizontal drag; taps and vertical
 *  swipes fall through to the scroll container so scrolling is never
 *  accidentally a value change. */
private fun Slider.dragToSeek(parentScroll: View) {
    var startX = 0f
    var startY = 0f
    var dragged = false
    setOnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.rawX; startY = e.rawY; dragged = false
                false                                // let slider get DOWN
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(e.rawX - startX)
                val dy = kotlin.math.abs(e.rawY - startY)
                if (!dragged && dx > 24f && dx > dy) {
                    // a real horizontal drag -> let the slider seek
                    parentScroll.parent?.requestDisallowInterceptTouchEvent(true)
                    dragged = true
                    false
                } else if (!dragged) {
                    true                            // vertical -> parent scrolls
                } else false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDrag = dragged
                dragged = false
                if (!wasDrag) true else false     // a pure tap -> consume, no seek
            }
            else -> false
        }
    }
}
```
Then:
```kotlin
binding.fontSizeSlider.dragToSeek(binding.settingsScroll)
binding.lineHeightSlider.dragToSeek(binding.settingsScroll)
binding.marginSlider.dragToSeek(binding.settingsScroll)
```
> Note: returning `true` to consume a pure tap stops the slider's tap-to-seek;
> returning `false` for a confirmed horizontal drag lets it seek normally. Test
> on a device — if your Android version's Slider behaves differently, the
> safe fallback is just layers A + B, which already make accidental changes
> rare.

#### D. (Optional) Reorder so sliders are last
Move all sliders below the toggle-button groups. Toggles don't seek on tap, so
the top of the sheet (the part users scroll through most) becomes
"scroll-safe". Sliders live at the bottom where users expect to drag.

---

## 6. Q4 — Hamburger drawer: add sections & change width

### Where the drawer lives
- **Layout:** `res/layout/activity_main.xml` — the `DrawerLayout` root contains
  the main `CoordinatorLayout` (content) and a `LinearLayout` with id
  `@+id/drawerPane` (the sliding panel). The drawer's rows are rendered by a
  `RecyclerView` with id `@+id/drawerList`.
- **Width:** set **programmatically** in `MainActivity.onCreate` (the XML
  `layout_width="300dp"` is overridden):
  ```kotlin
  // MainActivity.kt, ~line 310
  // Drawer takes 3/5 of screen width (ReadEra-style).
  val w = resources.displayMetrics.widthPixels
  binding.drawerPane.layoutParams =
      (binding.drawerPane.layoutParams as DrawerLayout.LayoutParams)
          .apply { width = (w * 3 / 5).coerceAtLeast(240) }
  ```
- **Row layout:** `res/layout/item_drawer.xml` (icon + label + count badge).
- **Adapter:** `ui/DrawerAdapter.kt` (renders `DrawerItem` rows; highlights the
  active section; detail views highlight their parent).
- **The list of sections:** `MainActivity.kt` → `drawerItems()` (~line 322).
- **The view states:** `ui/BookshelfViewModel.kt` → `sealed class ShelfView`
  (~line 26).

### Changing the drawer width
Edit the one line in `MainActivity.onCreate`:
```kotlin
.apply { width = (w * 3 / 5).coerceAtLeast(240) }
```
Examples:
- Fixed 320dp: `width = (320 * resources.displayMetrics.density).toInt()`
- 70% of screen: `width = (w * 70 / 100)`
- Remove the floor: drop `.coerceAtLeast(240)`.

(You can also just set `android:layout_width` on `drawerPane` in XML and delete
the programmatic override, but the programmatic version adapts to screen size.)

### Adding a new nav section (full recipe)
Suppose you want a new "Tags" section.

1. **Add a view state** in `BookshelfViewModel.kt`:
   ```kotlin
   sealed class ShelfView {
       ...
       object Tags : ShelfView()        // new
   }
   ```
2. **Add a drawer row** in `MainActivity.kt` → `drawerItems()`:
   ```kotlin
   DrawerItem(getString(R.string.nav_tags), R.drawable.ic_tag, view = ShelfView.Tags),
   ```
   (Order in this list = order in the menu.)
3. **Add the string** in `res/values/strings.xml`:
   ```xml
   <string name="nav_tags">Tags</string>
   ```
4. **Add an icon** `ic_tag.xml` in `res/drawable/`.
5. **Decide what the section shows:**
   - **If it's a placeholder ("coming soon"):** set `isPlaceholder = true` —
     the existing `setupObservers` already renders a "coming soon" empty state
     for placeholder views. You're done.
   - **If it's a real list:** wire it into the content flow:
     - In `BookshelfViewModel`, add a flow/transform that produces the data for
       `ShelfView.Tags` (mirror how `AuthorsList`/`SeriesList` build grouped
       rows from `BookDao`).
     - In `MainActivity.setupObservers()`, handle `ShelfView.Tags` (bind a
       `RowAdapter` or `BookAdapter` and decide list-vs-grid).
     - In `isBookView()` (MainActivity ~line 378) decide whether the new view
       is a "book view" (affects refresh/spinner enabling and back behavior).
     - In `titleFor(view)` (~line 343) add the section title.

> `Collections` and `Settings` are currently placeholders (`isPlaceholder = true`
> in `drawerItems()`). To make `Collections` real, you'd follow the recipe above
> and implement `CollectionDao`-backed content (the DAO + entity already exist).

---

## 7. App feature & gesture reference

### Main shelf (MainActivity)
- **Drawer (hamburger):** tap the toolbar icon (or swipe from the left edge) to
  open; shows Currently Reading, Library, Favorites, Authors, Series,
  Collections (placeholder), Folders, Settings.
- **Sections / views** (`ShelfView`): `Reading`, `Library`, `Favorites`,
  `Finished`, `AuthorsList`/`AuthorDetail`, `SeriesList`/`SeriesDetail`,
  `Collections`, `Folders`, `Settings`.
- **Toolbar menu:** Search, Sort, View mode, Import (the + on Library).
- **Sort options:** Recently added, Recently read, Title, Series, Author — each
  with Ascending/Descending (`showSortDialog()` / `showSortDirectionDialog()`).
- **View modes:** List, Grid 2, Grid 3, Grid 4 (`showViewModeDialog()`). Grid
  columns persisted in `prefs.gridColumns` (clamped 2–4).
- **Book interactions:**
  - **Tap** a book → open it in the reader.
  - **Long-press** a book → options sheet: Open, Details, Add/Remove Favorite,
    Remove from Currently Reading (if on the Reading list), Delete
    (`showBookOptions()`).
  - **Swipe left/right** on a book in the *Currently Reading* list → removes it
    from Currently Reading (`ItemTouchHelper.SimpleCallback`).
  - On grid views, tapping the book cover opens it; the info area opens details.
- **Scroll restoration (Patch 11):** returning to a list restores the previous
  scroll position; drawer navigation top-resets. Detail→parent restores (not
  top-resets).
- **FAB:** `fabScan` — `+` (import files) on Library; hidden on Folders (Patch 12
  removed the duplicate Folders FAB); hidden elsewhere.

### Folders view
- Inline buttons only (Patch 12): **Scan Now**, **Select Folder**, **Remove**
  (or just **Select Folder** when no folder is chosen).
- **Scan** runs as a tracked background job (`scanJob`) in `lifecycleScope` and
  survives in-app navigation (Patch 12). A duplicate-guard shows "Scan already
  in progress" if tapped while running. The spinner stays visible on every view
  until the scan finishes; on completion a snackbar reports new books (with a
  "Show" action to jump to Library).
- **Pull-to-refresh** is enabled on all views and triggers a rescan of the
  selected folder (safe — shows "Select a folder first" if none chosen).

### Settings view (app-level)
- "Scanned folder" row (the selected folder's display name).
- Reader theme row.
- About row.
- **Screen On toggle** (Patch 11): when on, keeps the screen awake for ~10
  minutes beyond the system screen-off timeout while the app is foregrounded
  (`KeepScreenOnController` — uses `FLAG_KEEP_SCREEN_ON` + a 10-min countdown
  that re-arms on user interaction). Persisted in `prefs.keepScreenOn`.

### Reader (ReaderActivity)
- **Tap zones:** left third = previous page, right third = next page, middle
  third = toggle chrome (top + bottom bars).
- **Horizontal swipe (fling):** right→left = next page, left→right = previous
  (requires >80px horizontal travel, horizontal dominance, velocity >500).
- **Chrome (top bar):** Back, TOC, Bookmarks, Search, Settings. **Bottom bar:**
  Prev/Next page, chapter SeekBar, Add-Bookmark.
- **Persistent page indicator:** faint "X / Y" above the nav bar, ink-colored.
- **Reader settings sheet** (`ReaderSettingsSheet`): Theme, Font, Alignment,
  Font size, Line spacing, Margin, Top & bottom margin, Hyphenation. Every
  control calls `onApply()` → `applySettingsAndReload()` which re-renders the
  current page and re-measures per-chapter page counts.
- **Book details:** cover, metadata, description; keep-screen-on while open.

### Reader themes
- **Light** (white bg / black ink), **Sepia** (#F1E3D3 bg / #5A4650 ink),
  **Dark** (#000000 bg / #FFFFFF ink). System status/nav bars are always solid
  black; only the page content area changes with the theme.

---

## 8. Settings reference (app-level + reader-level)

### App-level (`PrefsManager`, store = `epub_prefs`)
| Key | Field | Default | Notes |
| --- | --- | --- | --- |
| `view_grid` | `viewModeGrid` | `true` | list vs grid |
| `grid_columns` | `gridColumns` | `3` | clamped 2–4 |
| `sort` / `sort_dir` | `sortOption` / `sortAscending` | recently added / asc | |
| `tab` | `activeTab` | `0` | |
| `last_view` | `lastView` | `"library"` | restored on launch |
| `selected_folder_uri` | `selectedFolderUri` | `null` | SAF tree URI |
| `keep_screen_on` | `keepScreenOn` | `false` | Screen On toggle |

### Reader-level (`PrefsManager`)
| Key | Field | Default | Range / values | Used in |
| --- | --- | --- | --- | --- |
| `theme` | `theme` | `light` | light / sepia / dark | `applyWindowTheme` |
| `font` | `font` | `serif` | serif/sans/mono/book/humanist/publisher | `buildReaderCss` |
| `font_size` | `fontSize` | `24` | 24–56 (clamped) | `buildReaderCss` |
| `line_height` | `lineHeight` | `1.6` | 1.0–2.2 step 0.1 | `buildReaderCss` |
| `margin` | `margin` | `8` | 0–48 step 2 | `buildReaderCss` (horizontal padding + column gap) |
| `align` | `align` | left/justify | left/justify/center/right/original | `buildReaderCss` |
| `hyphenation` | `hyphenation` | `false` | bool | `buildReaderCss` |
| `bottom_guard` | `pageBottomMargin` | `true` | bool | `topGuardPx`/`bottomGuardPx` (56px top+bottom) |

---

## 9. Reader rendering internals (CSS + pagination JS)

The reader renders each EPUB chapter in a `WebView`. `buildChapterHtml()`
injects a `<style>` (from `buildReaderCss()`) and a `<script>` (from
`paginationJs(bottomGuardPx(), topGuardPx())`) into the chapter HTML.

- **CSS** sets the page background/ink, font family (unless "Publisher" which
  honors the book's embedded fonts), font size, line height, alignment,
  hyphenation, and horizontal padding + column gap (`padding:0 ${margin}px`,
  `column-gap:${2*margin}px`).
- **Pagination JS** uses CSS multi-column layout: each "page" is one column of
  the body. `apply()` sets `body.height = viewportHeight − TOP_GUARD − GUARD`,
  `body.marginTop = TOP_GUARD`, `html.height = height + TOP_GUARD`, so content
  renders from `TOP_GUARD` to `viewportHeight − GUARD` (symmetric top/bottom
  breathing room, painted with the reading bg color). Column advance = column
  width + gap, so column N+1 begins exactly at the right edge (no "sliver").
- A second, **offscreen measurement WebView** (`measureWebView`, INVISIBLE)
  loads the same HTML to count pages per chapter for the seek bar — so page
  counts stay consistent with the current margins/font.

### How reader settings re-render
`ReaderSettingsSheet` → every control's listener calls `onApply()` →
`ReaderActivity.applySettingsAndReload()` → re-applies theme, cancels in-flight
measurement, resets per-chapter page counts, and reloads the current chapter
with the new CSS/JS.

---

## 10. Data layer (Room + preferences)

- **Database:** `AppDatabase` (Room). Main entity: `BookEntity` (title, author,
  series, seriesIndex, path, coverPath, progress, scrollRatio, checksum,
  sourceFilename, sourceLastModified, fileSize, isFavorite, added/modified/
  lastOpened dates, spineIndex, pageMapCsv).
- **DAO:** `BookDao` — `getAll`, `getById`, `getByChecksum`, `getBySourceFilename`,
  `getSourceFingerprints`, author/series grouped queries, favorites, finished,
  currently-reading, search, plus progress/favorite/pageMap updates.
- **Cached files:** imported epubs → `app/files/epubs/<sha1>.epub`; covers →
  `app/files/covers/<sha1>.png`. Named by content hash (this is the dedup key).
- **Preferences:** `PrefsManager` wraps a single `SharedPreferences` file
  (`epub_prefs`). All reader + shelf prefs live here.

---

## 11. Themes & colors

- **App theme:** `res/values/themes.xml` —
  `Theme.MaterialComponents.DayNight.NoActionBar` (Material2, **not** Material3).
  This is why toggles use `SwitchCompat` (not `MaterialSwitch`, which crashes
  under Material2). If you ever migrate to Material3, revisit `SwitchCompat`
  usages.
- **Colors:** `res/values/colors.xml` — `accent` (FAB / drawer header),
  `reader_bg_light`, `reader_chrome_bg` (static eggplant chrome),
  `black`/`white` for system bars.
- **Reader content colors** are hard-coded in `ReaderActivity.readerColors()`
  (white/black/sepia bg + ink), **not** from color resources. To change reader
  page colors, edit `readerColors()`.
- **Chrome** (top/bottom bars, TOC/search overlays) uses `readerSurface()` which
  reads `R.color.reader_chrome_bg` (single source of truth in colors.xml).

---

## 12. Nuances & gotchas

- **`GridLayoutManager extends LinearLayoutManager`.** Any "is it linear?"
  check must also test for the more specific `GridLayoutManager` type —
  otherwise a leftover grid LM leaks into row lists (the Patch 12 Fix #2 bug).
  The safe pattern: `currentLm !is LinearLayoutManager || currentLm is GridLayoutManager`.
- **Back arrow on detail views.** `ActionBarDrawerToggle` draws **no icon** when
  `isDrawerIndicatorEnabled = false` — you must call
  `setHomeAsUpIndicator(R.drawable.ic_arrow_back)` yourself (Patch 12 Fix #1).
  Passing `0` clears it safely (does not call `Resources.getDrawable(0)`).
- **SwitchCompat vs MaterialSwitch.** The app is Material2 — use
  `SwitchCompat` for toggles. `MaterialSwitch` throws under Material2.
- **Slider initial value must be in range.** Material Slider throws if the
  initial `value` is outside `valueFrom..valueTo`. Always clamp stored prefs on
  read (see the font-size/margin clamping pattern) so an old install with an
  out-of-range value doesn't crash.
- **Scan scope.** The folder scan runs in the Activity `lifecycleScope`, which
  survives **in-app navigation** but is cancelled when the Activity is destroyed.
  If you ever need a scan to survive the app being backgrounded/screen-locked,
  move it into a **foreground service** that owns the coroutine and posts state
  back to the UI (see `READER_MARGIN_GUIDE.md`).
- **Dedup is two-layer.** Fast-skip by filename+size+mtime; fallback SHA-1
  content checksum. Never assume a re-scan will "clean up" duplicates by
  deleting — it only ever skips or updates. If you need true duplicate removal
  (e.g. two different source files with the same content), you'd add a
  DB-cleanup pass keyed on `checksum`.
- **`applicationId` is stable.** Only the display label (`app_name`) changes.
  Bump `versionCode` every release; keep the package name so installs are
  in-place upgrades that preserve data.
- **No `INTERNET` permission, ever.** Don't add any network permission — the
  app's whole premise is offline/privacy-first. Cover extraction, parsing,
  searching, and scanning are all local.
- **Strings are the single source of truth.** Add user-facing text only in
  `res/values/strings.xml`. For other languages, add `res/values-<locale>/`.
- **`emptyState` gravity.** The shared empty-state container is
  `gravity=center` by default; Folders/Settings override it to `START|TOP`
  in code (`showFoldersView()`/`showSettingsView()`).
- **Pull-to-refresh is global.** Patch 12 enabled the SwipeRefreshLayout on all
  views so the scan spinner stays visible everywhere. Pulling on any list
  triggers a folder rescan (safe if no folder is selected).
