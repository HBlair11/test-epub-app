# — The Livre Magicae Patch 18 Notes 

Version: <mark>1.19-patch18</mark> (versionCode 19) 

Build: debug APK, JDK 17 / Gradle 8.9 / AGP 8.7.2 / Kotlin 2.0.20 / compileSdk 35 / minSdk 24 Package: <mark>com.epubreader.app</mark> 

Toolchain: identical to your GitHub Actions workflow <mark>(.github/workfows/android-ci.yml)</mark> . Build with JDK 17 only — do NOT use 21+. 

This patch adds four things you asked for and fixes one icon inconsistency. Everything is designed as a single source of truth so future changes are one-place edits. 

## 0. Crash-safety: odd line-spacing / margin values (your question) 

No, the app will not crash on odd values. 

- Margin — the stepper changes by <mark>STEP_MARGIN = 2,</mark> so the UI only ever lands on even numbers (20 → 72). Even if an odd value were persisted, the reader CSS uses it as <mark>padding:0 <margin>px</mark> and <mark>column-gap:<2*margin>px</mark> — both valid for any integer. No crash path exists. 

- Line spacing — stored as a float but the stepper works in integer tenths 

- <mark>(Math.round(lineHeight * 10f)</mark> → tick 10..26 → 1.0..2.6). This is the Patch 17 fix that killed the old float-dri� crash (where <mark>1.9</mark> could internally become <mark>1.8999999)</mark> . Odd tenths like <mark>1.1</mark> , <mark>1.3</mark> , <mark>1.7</mark> are exact and safe. 

## 1. Addition #1 — Fast scroll on the book lists 

A draggable scrollbar (thumb + track) is now on the main book <mark>RecyclerView (@id/recycler</mark> in <mark>activity_main.xml)</mark> . It covers Library, Currently Reading, Author/Series, and Recently Added — every view that reuses that recycler, in both grid and list mode. 

- It uses the built-in <mark>androidx.recyclerview</mark> fast scroller (no new library). The thumb appears when the list is scrollable and is draggable — exactly the "click the scrollbar and move it up/down" behavior you described from desktop browsers / the Perplexity UI. 

No section labels (books aren't always A-Z), per your request. 

### Files: 

- `res/drawable/fast_scroll_thumb.xml` — a **`StateListDrawable`** (`<selector>`) wra 

   - <mark>res/drawable/fast_scroll_thumb_shape.xml</mark> — the actual rounded-pill shape, 18dp wide × 64dp tall (widened from 8dp in the first build a�er user feedback that the thumb was too small to grab with a thumb on a phone), color <mark>@color/fast_scroll_thumb</mark> (muted slate, ~70% opacity) so it's easy to see and grab. 

<mark>res/drawable/fast_scroll_track.xml</mark> — transparent (only the thumb is visible; no fullheight bar cluttering the list). 

- <mark>activity_main.xml</mark> — <mark>app:fastScrollEnabled="true"</mark> + the four <mark>fastScroll*Thumb/Track "</mark> 

- attributes on the RecyclerView. The recycler keeps <mark>paddingBottom="96dp</mark> so the thumb track clears the FAB (scan button). 

**To change the thumb color/size:** edit `fast_scroll_thumb_shape.xml` and `@color/fa 

Why not always-visible? Standard Android fast-scroll thumbs auto-show during scroll/drag. A permanently-visible, always-interactive scroller would require a custom view — more code, more to maintain, against your "no complexities" rule. The built-in thumb is the right trade-off. 

## 2. Addition #2 — App-wide fixed margins (spacing scale) 

A single semantic spacing scale now lives in <mark>res/values/dimens.xml</mark> . The reused chrome bars, overlays, settings, list rows, book-detail rows, main recycler, and search screen now reference these names instead of hardcoding <mark>dp</mark> , so changing a spacing value is a one-line edit that propagates across every screen that uses it. (A few visually-tuned paddings — book grid/list card internals — remain hardcoded where changing them would alter cover display; that's intentional.) 

|Dimen|Value|Used for|
|---|---|---|
|app_screen_edge_h|16dp|(reserved)horizontal screen-edge padding for top bars/list screens|
|app_chrome_padding_h|8dp|reader top/bottom bar+overlay title rows horizontal padding|
|app_chrome_padding_v|6dp|reader top/bottom bar+overlay vertical padding|
|app_grid_edge_h|10dp|book RecyclerView horizontal edge padding(grid+list)|
|app_row_padding_h|12dp|search input,overlay tab group,search-screen margins|
|app_row_padding_v|8dp|RecyclerView top padding,row vertical padding|
|app_row_spacing|8dp|(reserved)gap between stacked rows|
|app_card_padding|16dp|(reserved)card/surface internal padding|
|app_icon_button_size|44dp|icon-button touch target|
|app_seeker_button_size|40dp|seeker prev/next touch target|
|app_text_row_padding_h|6dp|title/author text rows next to a back button|
|app_row_content_padding_h|20dp|full-width list rows(drawer,TOC,search results,bookmarks,author/series,<br>book-detail rows)|



### What was standardized (real fixes): 

- <mark>item_bookmark.xml</mark> was asymmetric <mark>(20dp</mark> start / <mark>8dp</mark> end) → now symmetric <mark>app_row_content_padding_h.</mark> 

- <mark>item_author_series.xml</mark> was asymmetric <mark>(18dp</mark> / <mark>14dp</mark> ) → now symmetric. 

- All list-row items (drawer, TOC, search results, bookmarks, author/series, book-detail rows) now share <mark>app_row_content_padding_h.</mark> 

- Reader chrome (top bar, bottom bar, overlay title rows, search input, overlay tab group, text rows) now references the scale. 

Intentionally NOT touched (per advisor guidance — these are correct as-is or load-bearing): 

- The WebView reader content margins come from <mark>prefs.margin</mark> (your user setting) injected into the reader CSS — that is NOT layout padding and is separate by design. 

- The WebView <mark>column-gap</mark> ( <mark>2 * prefs.margin</mark> ). 

- Status-bar / nav-bar window insets (handled in code/themes). 

- Book grid/list card internal padding (visually tuned for cover display). 

To change a spacing everywhere: edit the one value in <mark>dimens.xml</mark> . To add a new spacing concept, add a <mark><dimen></mark> here and reference it from layouts — never hardcode <mark>dp</mark> in a layout. 

## 3. Addition #3 — EPUB-only import + "Open with" integration 

The app renders EPUB only (you confirmed PDF is out of scope). Import and scan are now restricted to EPUB, AND the app registers as a system handler so tapping an <mark>.epub</mark> anywhere offers "The Livre Magicae." 

### Single source of truth: <mark>epub/BookFileTypes.kt</mark> 

- <mark>ALL</mark> — accepted extensions (currently <mark>listOf("epub")</mark> ). 

- <mark>acceptedMimeTypes</mark> — MIME types passed to the SAF picker. 

- <mark>isBookFile(name)</mark> / <mark>isBookFile(uri, contentResolver)</mark> — extension validation. 

To add PDF (or any format) later: add it to <mark>ALL</mark> + <mark>acceptedMimeTypes</mark> in <mark>BookFileTypes.kt</mark> (covers the picker + scanner + the URI validator), AND add a matching <mark><intent-flter></mark> block in <mark>AndroidManifest.xml</mark> (the manifest is not auto-updated by the Kotlin helper). BUT the reader can't render a new format just by listing it — that needs a separate viewer (the EpubParser only parses EPUB zips; a non-EPUB file fails to parse and is silently not added). 

### Three consumers of <mark>BookFileTypes</mark> (the Kotlin helper): 

1. Import picker — <mark>openMultiFileLauncher.launch(BookFileTypes.acceptedMimeTypes)</mark> in <mark>MainActivity</mark> (was <mark>arrayOf("application/epub+zip", "application/epub", "*/*")</mark> — the <mark>"*/*"</mark> let all files show; removed). 

2. Folder scanner — <mark>EpubImporter.listEpubFiles</mark> / <mark>walk</mark> now use <mark>BookFileTypes.isBookFile(name)</mark> instead of a hardcoded <mark>.epub</mark> check. 

3. "Open with" handler — <mark>MainActivity.handleViewIntent</mark> validates incoming URIs with <mark>BookFileTypes.isBookFile(uri, contentResolver)</mark> , which is defensive: it queries <mark>OpenableColumns.DISPLAY_NAME</mark> in a try/catch (some <mark>fle://</mark> and non-Documents 

<mark>content://</mark> providers throw on <mark>query()</mark> ) and falls back to <mark>uri.lastPathSegment</mark> / <mark>uri.path</mark> . Never throws. 

The manifest intent filter is separate and hand-written (Kotlin constants do not update <mark>AndroidManifest.xml)</mark> . It registers <mark>ACTION_VIEW</mark> for <mark>application/epub+zip</mark> + <mark>application/epub</mark> , schemes <mark>content</mark> / <mark>fle</mark> , plus <mark>.epub</mark> path patterns as a best-effort extra. Most file managers send the correct EPUB MIME and are covered; an epub arriving as <mark>application/octet-stream</mark> with a <mark>.epub</mark> name may still be matched by the path pattern, but this is not guaranteed across all providers — don't assume "any EPUB anywhere" will route here. If you add a format, edit <mark>BookFileTypes.kt</mark> and add a matching <mark><intent-flter></mark> block in the manifest. 

Incoming-URI handling — <mark>MainActivity.handleViewIntent(intent)</mark> : 

- Called from <mark>onCreate</mark> and <mark>onNewIntent</mark> (so a fresh launch AND a re-delivery to the running instance both work). 

- Validates the file type client-side (defense in depth — the filter already scopes to EPUB). 

- Imports via <mark>EpubImporter.importUri(uri),</mark> then opens <mark>ReaderActivity</mark> with the new book id. 

- On failure: snackbar <mark>"Could not import this fle."</mark> ; on unsupported type: <mark>"Only EPUB fles can be imported."</mark> 

New strings: <mark>import_unsupported_fle</mark> , <mark>import_failed</mark> in <mark>strings.xml</mark> . 

" " Testing Open with": install the APK, then in any file manager tap an <mark>.epub</mark> → the system Open with" sheet lists The Livre Magicae. Tapping it imports the book and opens it in the reader. 

## 4. Fix #1 — Icon/drawable sync + single source of truth 

The discrepancy: <mark>ic_chevron_left</mark> and <mark>ic_chevron_right</mark> each had <mark>fllColor="@android:color/white"</mark> on an open path. Android implicitly closes open paths when filling, so each chevron rendered as a solid filled triangle — but because the two had slightly different path geometry, one looked heavier than the other (the right seeker arrow looked filled while the le� looked thin-line). The back arrow <mark>(ic_arrow_back)</mark> had the same <mark>fllColor=white</mark> defect and was also a filled triangle. 

Fix: set <mark>fllColor</mark> to <mark>@android:color/transparent</mark> on all three ( <mark>ic_chevron_left, ic_chevron_right, ic_arrow_back)</mark> so each renders as a thin-line stroke chevron <mark>(strokeWidth=2,</mark> round cap/join, transparent fill). All back / previous / next arrows are now the same thin-line style. 

### Files: 

- <mark>res/drawable/ic_chevron_left.xml</mark> — rewritten to a thin-line stroke chevron 

- <mark>(strokeWidth=2,</mark> round cap/join, transparent fill), mirroring <mark>ic_chevron_right.</mark> 

- <mark>res/values/styles.xml</mark> — new shared style <mark>EpubReaderIconButton</mark> : the single source of truth for every icon button's size (44dp), background ripple 

- <mark>(selectableItemBackgroundBorderless)</mark> , and tint policy. Applied to all reader icon buttons 

(back / toc / bookmarks / search / settings / prev / next / overlay-back / search-back) and <mark>item_bookmark.xml'</mark> s delete button. 

Note on the style name: it is a single segment ( <mark>EpubReaderIconButton,</mark> no dots). AAPT2 treats dotted style names like <mark>Widget.EpubReader.IconButton</mark> as an inheritance chain and errors if an implicit parent ( <mark>Widget.EpubReader)</mark> is missing — so the name has no dots. Keep it dot-free if you rename it. 

Icon tint: the icons are white vector drawables used on the reader chrome / overlays (dark backgrounds), so they render white from their own color — no per-button tint needed. The <mark>EpubReaderIconButton</mark> style therefore centralizes size + background ripple only (not tint). If you apply this style on a light background, set <mark>app:tint</mark> on the view (e.g. ? 

<mark>android:textColorPrimary</mark> ) or the white icon will be invisible. The delete button sets <mark>app:tint="?android:textColorSecondary"</mark> explicitly. 

## Icon → drawable → usage table (the single source of truth) 

|Action|Drawable|Used in|
|---|---|---|
|Back/up|ic_arrow_back|reader top bar,overlay title bars,search bar|
|Previous page|ic_chevron_left|reader bottom seeker|
|Next page|ic_chevron_right|reader bottom seeker|
|Table of contents|ic_toc|reader top bar|
|Bookmarks|ic_bookmark_border|reader top bar(outline=not bookmarked)|
|Bookmark(filled)|ic_bookmark|shown when bookmarked—intentionally filled=active state,do NOT<br>convert to outline|
|Search|ic_search|reader top bar|
|Settings|ic_settings|reader top bar|
|Add|ic_add|FAB/ +button|
|Decrease|ic_minus|reader settings steppers|
|Increase|ic_add_circle|reader settings steppers|
|Close|ic_close|dialogs|
|Menu<br>(hamburger)|ic_menu|drawer toggle|
|Delete|ic_delete|bookmark row|
|Grid view|ic_grid|view toggle|
|List view|ic_list|view toggle|
|Sort|ic_sort|sort menu|
|More|ic_more_vert|row overflow|



Rule: when adding a reused action, use the existing drawable from this table — do not create a near-duplicate. If you need a new icon, add ONE drawable here and reference it everywhere. Keep all line icons <mark>strokeWidth=2,</mark> round caps/joins, transparent fill, 24dp viewport, so the set stays visually consistent. 

## 5. Build & verify 

- # from the project root, with JDK 17 + Android SDK 35 on PATH ./gradlew assembleDebug --no-daemon --console=plain 

Verified on this build: 

- <mark>BUILD SUCCESSFUL.</mark> 

- Badging: <mark>versionCode=19 versionName=1.19-patch18,</mark> package <mark>com.epubreader.app</mark> . 

- Permissions: only <mark>DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION</mark> — no INTERNET (unchanged). 

- Manifest: <mark>MainActivity</mark> exported + <mark>singleTop</mark> + <mark>ACTION_VIEW/application/epub+zip</mark> intent filter present. 

- Dex: <mark>BookFileTypes</mark> class packaged; <mark>fast_scroll_thumb.xml</mark> / <mark>fast_scroll_track.xml</mark> / rewritten <mark>ic_chevron_left.xml</mark> packaged. 

- Unit tests: 18 pass; the 2 <mark>EpubParserTest</mark> failures are pre-existing and environmental (they ' 

- need a <mark>sample.epub</mark> fixture at <mark>/home/user/workspace/sample.epub</mark> that isn t in this sandbox) — unchanged from Patch 17, unrelated to this patch. 

## 6. Files changed in Patch 18 

New: 

- <mark>epub/BookFileTypes.kt</mark> — file-type single source of truth. 

- <mark>res/values/dimens.xml</mark> — app-wide spacing scale. 

- <mark>res/drawable/fast_scroll_thumb.xml, fast_scroll_track.xml.</mark> 

### Modified: 

- <mark>app/build.gradle.kts</mark> — versionCode 19 / <mark>1.19-patch18</mark> . 

- <mark>AndroidManifest.xml</mark> — <mark>singleTop</mark> + EPUB <mark>ACTION_VIEW</mark> intent filter on MainActivity. 

- <mark>MainActivity.kt</mark> — <mark>BookFileTypes</mark> import; picker launches with <mark>acceptedMimeTypes</mark> ; <mark>onNewIntent</mark> + <mark>handleViewIntent</mark> for incoming EPUB URIs. 

- <mark>epub/EpubImporter.kt</mark> — folder scan uses <mark>BookFileTypes.isBookFile().</mark> 

- <mark>res/values/strings.xml</mark> — <mark>import_unsupported_fle, import_failed.</mark> 

- <mark>res/values/colors.xml</mark> — <mark>fast_scroll_thumb</mark> . 

- <mark>res/values/styles.xml</mark> — <mark>EpubReaderIconButton</mark> shared icon-button style. 

<mark>res/drawable/ic_chevron_left.xml</mark> — thin-line stroke. 

- <mark>res/layout/activity_reader.xml</mark> — icon buttons use <mark>EpubReaderIconButton</mark> ; 

- chrome/overlay/text-row padding → dimens. 

- <mark>res/layout/activity_main.xml</mark> — RecyclerView fast-scroll enabled; recycler padding → dimens. 

- <mark>res/layout/activity_reader_settings.xml, activity_book_details.xml,</mark> 

<mark>activity_search.xml, item_drawer.xml</mark> , <mark>item_toc.xml</mark> , <mark>item_search_result.xml</mark> , 

<mark>item_bookmark.xml, item_author_series.xml</mark> — padding → dimens scale. 

### Deleted: none. 

## 7. Maintenance cheat-sheet 

- Change a spacing app-wide → edit <mark>res/values/dimens.xml.</mark> 

- Change the fast-scroll thumb color → edit <mark>@color/fast_scroll_thumb</mark> in <mark>colors.xml.</mark> 

- Add a new icon → one drawable in <mark>res/drawable/</mark> , list it in the table in §4, use it everywhere; keep <mark>strokeWidth=2</mark> /round caps/transparent fill. 

- Add a new accepted file type → edit <mark>BookFileTypes.kt (ALL</mark> + <mark>acceptedMimeTypes</mark> ); remember the reader still needs a viewer for the new format. 

- Change icon-button size/touch target → edit <mark>EpubReaderIconButton</mark> in <mark>styles.xml.</mark> 

