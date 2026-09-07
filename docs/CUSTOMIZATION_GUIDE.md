# Customization Guide

How to find and change anything in Caesura. Every section lists the **exact file(s)
and what to edit**, plus the build step to verify. File paths are relative to the
project root (`EpubReader/`).

> After any change, run `./scripts/build.sh` (or `./scripts/validate.sh` for a
> full check). See [BUILD_AND_VALIDATION.md](BUILD_AND_VALIDATION.md).

All source is under `app/src/main/java/com/epubreader/app/`.
All resources are under `app/src/main/res/`.

---

## Table of Contents

1. [App name & package](#1-app-name--package)
2. [App icon](#2-app-icon)
3. [Colors / theme](#3-colors--theme)
4. [Navigation drawer](#4-navigation-drawer)
5. [Bookshelf views (grid/list/columns)](#5-bookshelf-views-gridlistcolumns)
6. [Sorting](#6-sorting)
7. [Search screen](#7-search-screen)
8. [Book details screen](#8-book-details-screen)
9. [Reader: pagination & page turns](#9-reader-pagination--page-turns)
10. [Reader: settings (theme/font/size/line/margin/align)](#10-reader-settings)
11. [Reader: tap zones & chrome](#11-reader-tap-zones--chrome)
12. [Reader: TOC, bookmarks, in-book search](#12-reader-toc-bookmarks-in-book-search)
13. [EPUB engine (parsing, covers, resources, search)](#13-epub-engine)
14. [Database schema & migrations](#14-database-schema--migrations)
15. [Preferences (persisted settings)](#15-preferences-persisted-settings)
16. [Adding a new screen / activity](#16-adding-a-new-screen--activity)
17. [System bars / edge-to-edge insets](#17-system-bars--edge-to-edge-insets)
18. [Permissions](#18-permissions)

---

## 1. App name & package

- **Display name**: `app/src/main/res/values/strings.xml` → `<string name="app_name">Caesura</string>`.
- **Package / applicationId**: `app/build.gradle.kts` → `namespace` and `applicationId`
  (both `com.epubreader.app`). To rename the package you must also move the
  source directory and update imports; prefer just changing `app_name` unless you
  need a different application ID.
- **APK output filename**: produced at `app/build/outputs/apk/debug/app-debug.apk`.

## 2. App icon

The icon is a raster image (your uploaded book-stack illustration), exposed both as
PNG mipmaps and an adaptive-icon foreground.

- **Source image**: `app/src/main/res/drawable-nodpi/ic_launcher_foreground_image.png`
  (432×432). This is the single source — replace this file to change the icon.
- **Foreground (adaptive icon, API 26+)**:
  `app/src/main/res/drawable/ic_launcher_foreground.xml` — a `<bitmap>` pointing at
  the source image above with `android:gravity="fill"`.
- **Background (adaptive icon)**: `app/src/main/res/drawable/ic_launcher_background.xml`
  — currently solid white.
- **PNG fallbacks (API < 26)**: `app/src/main/res/mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher.png`
  and `ic_launcher_round.png`.
- **Adaptive icon XML**: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and
  `ic_launcher_round.xml`.

**To change the icon:**
1. Replace `drawable-nodpi/ic_launcher_foreground_image.png` with your new 432×432 PNG.
2. Regenerate the density PNGs from it (square, cropped to center). A helper:
   ```bash
   python3 - <<'PY'
   from PIL import Image
   im=Image.open("app/src/main/res/drawable-nodpi/ic_launcher_foreground_image.png").convert("RGBA")
   s=min(im.size); l=(im.width-s)//2; t=(im.height-s)//2; im=im.crop((l,t,l+s,t+s))
   for dpi,px in {'mdpi':48,'hdpi':72,'xhdpi':96,'xxhdpi':144,'xxxhdpi':192}.items():
       img=im.resize((px,px), Image.LANCZOS)
       img.save(f"app/src/main/res/mipmap-{dpi}/ic_launcher.png")
       img.save(f"app/src/main/res/mipmap-{dpi}/ic_launcher_round.png")
   PY
   ```
3. (Optional) change `ic_launcher_background.xml` to a different color.
4. Rebuild.

## 3. Colors / theme

See **[THEME_COLORS.md](THEME_COLORS.md)** for the full palette and the
single-source-of-truth procedure. Short version:
- Palette: `app/src/main/res/values/colors.xml`
- Light theme: `app/src/main/res/values/themes.xml`
- Dark theme: `app/src/main/res/values-night/themes.xml`
- Reader hex (must mirror palette): `ReaderActivity.kt` → `readerColors()` / `readerSurface()`
- Badge hex: `app/src/main/res/drawable/badge_bg.xml`

## 4. Navigation drawer

- **Layout**: `app/src/main/res/layout/activity_main.xml` — the `#id/drawerPane`
  LinearLayout (`layout_gravity="start"`). Its width is set to 3/5 of screen
  width in code (see below).
- **Drawer host**: `MainActivity.kt` → `setupDrawer()` — sets the drawer width to
  `widthPixels * 3 / 5` and binds `DrawerAdapter`.
- **Drawer items list + icons**: `MainActivity.kt` → `drawerItems()`. Each
  `DrawerItem(label, iconRes, view = ShelfView.X)`. To add/remove a drawer
  destination, edit this list. Icons are vector drawables in `res/drawable/`
  (e.g. `ic_book`, `ic_favorite`, `ic_collections`, `ic_folders`, `ic_settings`).
- **Drawer item row layout**: `app/src/main/res/layout/item_drawer.xml`.
- **Hamburger button**: set on the toolbar via `app:navigationIcon="@drawable/ic_menu"`
  in `activity_main.xml`; click handled in `MainActivity.kt` →
  `binding.toolbar.setNavigationOnClickListener { toggleDrawer() }`.
- **Drawer open/close + back press**: `MainActivity.kt` → `toggleDrawer()` and the
  `OnBackPressedCallback` in `onCreate`.

**To change drawer width** (e.g. to 4/5): `MainActivity.kt` → `setupDrawer()` →
`width = (w * 3 / 5)` → change `3/5` to `4/5`.

## 5. Bookshelf views (grid/list/columns)

- **Grid item layout** (cover shape, title/author, progress badge):
  `app/src/main/res/layout/item_book_grid.xml`. Cover aspect ratio is
  `app:layout_constraintDimensionRatio="H,1:1.5"` on the `#id/cover` ImageView
  (portrait, height = 1.5 × width). Change `1:1.5` to make covers taller/shorter.
- **List item layout**: `app/src/main/res/layout/item_book_list.xml`.
- **Grid/List toggle + column count**: `MainActivity.kt` → `showViewModeDialog()`.
  Options: List, Grid 2, Grid 3, Grid 4. Calls `viewModel.setViewMode(...)` and
  `viewModel.setGridColumns(n)`.
- **Default view mode / columns**: `PrefsManager.kt` → `viewModeGrid` (default
  `true`) and `gridColumns` (default `3`).
- **Grid column count application**: `BookshelfViewModel.kt` exposes
  `viewModeGrid` and `gridColumns` as LiveData; `MainActivity.kt` observes them in
  `setupObservers()` and calls `reconfigureAdapter()` so changes apply immediately.
- **Adapter**: `app/src/main/java/com/epubreader/app/ui/BookAdapter.kt` — handles
  both grid and list holders.

## 6. Sorting

- **Sort dialog (field → asc/desc)**: `MainActivity.kt` → `showSortDialog()` then
  `showSortDirectionDialog(fieldLabel, fieldKey)`.
- **Sort options available**: `PrefsManager.kt` → `object SortOption`
  (`RECENTLY_ADDED`, `TITLE`, `SERIES`, `AUTHOR`).
- **Sort logic (asc/desc)**: `BookshelfViewModel.kt` → `applySort(list, sort, asc)`.
  Returns ascending list, then `.reversed()` if descending.
- **Scroll-to-top after sort**: `MainActivity.kt` sets `scrollToTopOnNextContent =
  true` before applying, and the content observer scrolls to position 0.

## 7. Search screen

- **Activity**: `app/src/main/java/com/epubreader/app/SearchActivity.kt`.
- **Layout**: `app/src/main/res/layout/activity_search.xml`.
- **Launch**: `MainActivity.kt` → `launchSearch()` (toolbar search action).
- **Query + debounce**: `SearchActivity.kt` → `query` StateFlow, `.debounce(180)`,
  `.flatMapLatest { repo.search(q) }`.
- **Backend**: `BookRepository.kt` → `search(query)` → `BookDao.kt` → `search(q)`
  (LIKE on title/author/series).

## 8. Book details screen

- **Activity**: `BookDetailsActivity.kt`. **Layout**: `res/layout/activity_book_details.xml`.
- **Hero cover size**: `activity_book_details.xml` → `#id/cover` `android:layout_height="300dp"`.
- **Metadata rows**: `BookDetailsActivity.kt` → `bind(book)` calls `addRow(...)`.
  To show/hide a field (e.g. re-add identifier), add/remove an `addRow(...)` line.
- **Filename shown**: `addRow(getString(R.string.book_file), book.sourceFilename ?:
  File(book.path).name)` — shows the original imported filename.

## 9. Reader: pagination & page turns

The reader uses **CSS multi-column pagination** in a WebView. There is no JS/TS
build step — the pagination script is a Kotlin string injected into each chapter's
HTML.

- **Activity**: `ReaderActivity.kt`. **Layout**: `res/layout/activity_reader.xml`.
- **Pagination script**: `ReaderActivity.kt` → the `PAGINATION_JS` constant. It
  exposes `window.Caesura = { apply, pageCount, currentPage, gotoPage, nextPage,
  prevPage, ratio, gotoElementById }` using horizontal `scrollLeft` through CSS
  columns (`column-width = body.clientWidth`, `column-gap = 0`).
- **CSS columns setup**: `ReaderActivity.kt` → `buildReaderCss()` — sets
  `html,body { height:100%; overflow:hidden }` and `body { column-fill:auto;
  padding:0 ${margin}px; ... }`.
- **Page turn (tap + buttons)**: `ReaderActivity.kt` → `turnPage(forward)` calls
  `Caesura.nextPage()/prevPage()`; returns `'next-chapter'`/`'prev-chapter'` at
  edges → `goToSpine(±1)`.
- **Tap zones**: `ReaderActivity.kt` → `setupChrome()` GestureDetector:
  `x < w/3` = prev, `x > w*2/3` = next, center = toggle chrome.
- **Progress**: `pollProgress()` reads `Caesura.ratio()` (page/pageCount);
  `updateOverallProgress()` saves `(spineIndex + ratio)/spineCount` to the DB.
- **Restore position**: `applyPendingFragmentOrRestore()` → `gotoPage(round(ratio*pageCount))`.
- **Re-paginate on settings change**: `applySettingsAndReload()` preserves the
  page ratio and reloads the chapter.

**To change page-turn behavior** (e.g. make tap zones 40/20/40): edit the
`when` block in `setupChrome()`.

## 10. Reader: settings

- **Settings sheet**: `app/src/main/java/com/epubreader/app/ui/ReaderSettingsSheet.kt`.
- **Sheet layout**: `res/layout/dialog_reader_settings.xml` — includes sliders:
  - Font size: `fontSizeSlider` `valueFrom="10" valueTo="40"`.
  - Line height: `lineHeightSlider` `1.0–2.2`.
  - Margin: `marginSlider` `0–48`.
  - Theme buttons, font buttons, alignment buttons.
- **Persisted values + defaults**: `PrefsManager.kt` → `theme`, `font`,
  `fontSize` (default 18), `lineHeight` (1.6), `margin` (8), `justify` (false).
- **CSS application**: `ReaderActivity.kt` → `buildReaderCss()` reads prefs and
  emits the `<style>` block applied to the chapter HTML.

## 11. Reader: tap zones & chrome

- **Chrome bars**: `res/layout/activity_reader.xml` → `#id/topBar` and
  `#id/bottomBar` (both `visibility="gone"` until toggled).
- **Toggle chrome**: `ReaderActivity.kt` → `toggleChrome()`.
- **Chrome buttons**: `setupChrome()` wires btnBack, btnToc, btnBookmarks,
  btnSearch, btnSettings, btnMore, btnPrev, btnNext, tvAddBookmark.

## 12. Reader: TOC, bookmarks, in-book search

- **TOC**: `ReaderActivity.kt` → `showToc()` + `tocMap()`; adapter
  `ui/TocAdapter.kt`; layout `res/layout/item_toc.xml`.
- **Bookmarks**: `ReaderActivity.kt` → `showBookmarks()`, `addBookmark()`,
  `goToBookmark(b)`; DAO `data/BookmarkDao.kt`; entity
  `data/BookmarkEntity.kt`; adapter `ui/BookmarkAdapter.kt`; layout
  `res/layout/item_bookmark.xml`.
- **In-book search**: `ReaderActivity.kt` → `showSearch()` + `performSearch()`;
  engine `epub/EpubSearchEngine.kt`; layout `res/layout/bottom_sheet_search.xml`;
  adapter `ui/SearchResultAdapter.kt`.

## 13. EPUB engine

All under `app/src/main/java/com/epubreader/app/epub/`:

| File                  | Responsibility                                                        |
|-----------------------|-----------------------------------------------------------------------|
| `EpubParser.kt`        | Unzips EPUB, parses `container.xml` → OPF → metadata, spine, manifest |
| `EpubModels.kt`        | Data classes: `EpubBook`, `EpubManifestItem`, `EpubTocEntry`, metadata |
| `EpubPaths.kt`         | Path resolution helpers inside the ZIP                                |
| `EpubResourceResolver.kt` | `shouldInterceptRequest` for WebView: resolves internal EPUB resources by virtual host |
| `CoverExtractor.kt`    | Extracts cover image from the EPUB to a file                           |
| `EpubImporter.kt`      | Imports from SAF Uri/file, computes checksum, copies to app-private storage, parses, upserts Room row |
| `EpubSearchEngine.kt`  | Full-text search across spine items                                   |

**To support a new EPUB feature** (e.g. SVG cover, fixed-layout): start in
`EpubParser.kt` to parse the structure, then handle rendering in
`ReaderActivity.kt` / `buildChapterHtml()` / `EpubResourceResolver.kt`.

## 14. Database schema & migrations

- **Entities**: `data/BookEntity.kt`, `data/BookmarkEntity.kt`,
  `data/CollectionEntity.kt`, `data/BookCollectionRef.kt`.
- **DAOs**: `data/BookDao.kt`, `data/BookmarkDao.kt`, `data/CollectionDao.kt`.
- **Database + migrations**: `data/AppDatabase.kt`. Current version = **2**.
  `MIGRATION_1_2` adds `is_favorite` and `source_filename`. The DB also has
  `fallbackToDestructiveMigration()` as a safety net.
- **Repository**: `data/BookRepository.kt` wraps DAOs and exposes Flows.

**To add a column / change schema:**
1. Edit the entity (e.g. add a `@ColumnInfo` field to `BookEntity`).
2. Bump `@Database(version = N)` in `AppDatabase.kt`.
3. Add a `Migration(N-1, N)` in the companion object and register it in
   `.addMigrations(...)`. For a simple ALTER TABLE, write the SQL in `migrate()`.
4. (Optional) Add a DAO `@Query`/`@Update` method and expose it via `BookRepository`.
5. Rebuild; Room/KSP validates the schema at compile time.

> If you skip the migration and only bump the version, `fallbackToDestructiveMigration`
> will wipe the user's library on update — always provide a real migration for
> schema changes you want to ship.

## 15. Preferences (persisted settings)

- **File**: `data/PrefsManager.kt` — wraps `SharedPreferences` ("epub_prefs").
- **Keys**: defined in the `companion object` (`KEY_GRID`, `KEY_GRID_COLS`,
  `KEY_SORT`, `KEY_SORT_DIR`, `KEY_LAST_VIEW`, `KEY_THEME`, `KEY_FONT`,
  `KEY_FONT_SIZE`, `KEY_LINE_HEIGHT`, `KEY_MARGIN`, `KEY_JUSTIFY`).
- **To add a persisted setting**: add a `var x: T` getter/setter + a `KEY_X`
  constant, then read it where needed (e.g. in `ReaderActivity` or
  `BookshelfViewModel`).

## 16. Adding a new screen / activity

1. Create `FooActivity.kt` in `app/src/main/java/com/epubreader/app/`.
2. Create `res/layout/activity_foo.xml`.
3. Register in `app/src/main/AndroidManifest.xml`:
   ```xml
   <activity android:name=".FooActivity"
       android:exported="false"
       android:configChanges="orientation|screenSize|keyboardHidden"
       android:theme="@style/Theme.EpubReader" />
   ```
4. Launch: `startActivity(Intent(this, FooActivity::class.java))`.

## 17. System bars / edge-to-edge insets

`targetSdk = 35` enforces edge-to-edge (content draws behind status & nav bars).
We handle insets per-screen:

- **Reader**: `ReaderActivity.kt` → `ViewCompat.setOnApplyWindowInsetsListener(binding.root)`
  pads the root by `systemBars()` insets. The WebView's `innerHeight/innerWidth`
  then exclude the bars, so paginated pages fill exactly the safe area.
- **Bookshelf**: `activity_main.xml` root `DrawerLayout` has
  `android:fitsSystemWindows="true"` (pads content + drawer).
- **Details**: `activity_book_details.xml` root has `android:fitsSystemWindows="true"`.

If a new screen's controls clash with the nav bar, add the same
`setOnApplyWindowInsetsListener` or `fitsSystemWindows="true"` to its root.

## 18. Permissions

The app uses **no dangerous permissions** and **no INTERNET**. File access uses
the Storage Access Framework (`OpenDocumentTree` / `OpenDocument`), which needs
no runtime permission. See `AndroidManifest.xml`.

To verify the APK declares no network permission:
```bash
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```
