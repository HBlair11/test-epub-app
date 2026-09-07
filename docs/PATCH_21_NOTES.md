# — The Livre Magicae Patch 21 Maintenance Guide 

This guide documents Patch 21 (versionCode <mark>22,</mark> versionName <mark>1.21-patch21)</mark> , the "whole-app spacing sync." It explains the single keyline every screen now aligns to, what changed and why, and the repeatable pattern for keeping future layouts in sync. Patch 20's changes (removed chevron row, restored scrollbar, reconstructed root files) are all preserved — Patch 21 only touches spacing. 

## 1. What changed in Patch 21 

The goal was "equal margin spaces on all edges, le�/right/top/bottom, everywhere a component touches the screen." The root cause of the unevenness was double-padding: list rows and cards added their own horizontal padding on top of the main RecyclerView's 10dp edge padding, so content landed at 26–30dp while the toolbar sat at 16dp. Patch 21 fixes this by aligning all content edges to one keyline (20dp) and making every element symmetric le� = right. 

|#|Change|File(s)|
|---|---|---|
|1|Author/Series rows:padding20dp→ 10dp so content lands at<br>20dp(10dp recycler+ 10dp)instead of30dp|item_author_series.xml|
|2|Book-grid covers:margin6dp→ 10dp so covers land at the20dp<br>keyline|item_book_grid.xml|
|3|Section header:padding4dp→ 10dp(aligns to20dp keyline)|item_section_header.xml|
|4|Search screen box:margin12dp→ 20dp+added marginTop8dp<br>(symmetric top/bottom)|activity_search.xml|
|5|Search screen results:recycler padding6dp→ 10dp so result<br>cards align to the20dp keyline(10dp+ 10dp item margin)|activity_search.xml|
|6|In-reader search overlay restructured to two rows:back arrow<br>alone(chrome keyline)then full-width search box at20dp L/R—<br>box now has equal margins on all edges and aligns with the<br>search results below|activity_reader.xml<br>(<br>searchOverlay)|
|7|Reader bottom bar:added marginTop6dp to the% /Add<br>Bookmark row so it has the same vertical spacing as the icon-<br>button rows|activity_reader.xml (<br>bottomBar)|
|8|TOC/Bookmarks overlay:added a44dp right spacer so the<br>centered title is truly centered(balances the44dp back button)|activity_reader.xml<br>(<br>tocBookmarkOverlay)|
|—|Version bump|app/build.gradle.kts →<br>versionCode<br>22,<br>versionName"1.21-patch21"|



No Kotlin code changed in Patch 21 — it is pure layout XML. All view-binding IDs were preserved (notably <mark>btnSearchBack, searchEdit, searchContent)</mark> , so no 

code references needed updating. 

## 2. The one keyline: 20dp content edge 

Every screen's content now aligns to a single 20dp content keyline. This is the same value the Reader Settings screen (the stated gold standard) already used for its content rows. Toolbars stay at the Material default 16dp content inset — exactly like the Reader Settings toolbar — so the 4dp step from a toolbar title down to its content is consistent app-wide. 

## How content reaches 20dp 

The main bookshelf <mark>RecyclerView</mark> has <mark>paddingStart/End = app_grid_edge_h</mark> (10dp). Children add another 10dp of their own edge padding/margin, so content lands at 10 + 10 = 20dp from the screen edge: 

|Element|Edge source|Math|Result|
|---|---|---|---|
|Author/Series row text|row padding10dp+recycler10dp|10 + 10|20dp|
|Book-grid cover le�|card margin10dp+recycler10dp|10 + 10|20dp|
|Book-list card le�|card margin10dp+recycler10dp|10 + 10|20dp|
|Search screen box|box margin20dp(own screen)|20|20dp|
|Search screen result card|recycler10dp+card margin10dp|10 + 10|20dp|
|Reader overlay rows(TOC/bookmarks/search)|item padding20dp|20|20dp|
|Reader bottom-bar text(% +bookmark)|bar10dp+child padding10dp|10 + 10|20dp|
|Reader top-bar title rows|bar10dp+child padding10dp|10 + 10|20dp|



## The single source of truth 

All spacing lives in <mark>app/src/main/res/values/dimens.xml.</mark> The values that form the 20dp keyline: 

|Dimension|Value|Role|
|---|---|---|
|app_row_content_padding_h|20dp|The keyline.Full-width content rows,search box,reader overlay items,<br>reader settings content|
|app_grid_edge_h|10dp|Main RecyclerView edge+per-item edge compensation(10 + 10 = 20)|
|app_chrome_padding_h|10dp|Reader chrome container edge(icon buttons sit here→glyph at20dp)|
|app_reader_icon_inset|10dp|Glyph inset inside a44dp icon button→glyph le�edge at20dp|
|app_chrome_padding_v|6dp|Vertical padding inside chrome bars(top/bottom bar rows)|
|app_row_padding_v|8dp|Vertical margin around the search box(top+bottom,symmetric)|
|app_row_spacing|8dp|Gap between the search box and the results list below it|



|Dimension|Value|Role|
|---|---|---|
|app_icon_button_size|44dp|Icon button/spacer width used to center overlay titles|
|app_screen_edge_h|16dp|Toolbar content inset(Material default);le�for toolbars|



To shi� the whole app's content edge, change <mark>app_grid_edge_h</mark> (and the matching item edges) — but you'd rarely need to. The 20dp keyline is the deliberate gold standard; keep new content on it. 

## 3. Per-screen alignment a�er Patch 21 

- Library (bookshelf ): covers and list cards align to 20dp; the toolbar title sits at 16dp (Material default), matching Reader Settings' toolbar. 

- Author / Series / Collection rows: text, icon, count chip, and chevron all sit on 20dp — le� and right symmetric. No more 30dp double-indent. 

- Search screen (SearchActivity): the search box has equal 20dp le�/right and 8dp top/bottom. Result cards align to the same 20dp keyline as the box. Scrollbar preserved ( <mark>android:scrollbars="vertical")</mark> . 

- In-reader search overlay: two rows — back arrow at the chrome keyline, then the search box full-width at 20dp le�/right with 8dp below it. The box's le� and right edges align with the search results (item rows at 20dp). 

- Reader bottom bar: the seek bar, the %, and Add Bookmark all sit on the 20dp keyline. The %/Add Bookmark row now has 6dp top margin, matching the icon-button rows' vertical rhythm. 

- TOC / Bookmarks overlay: the centered title is now truly centered (a 44dp right spacer balances the 44dp back button). Tab group and item rows stay at 20dp. 

## 4. The in-reader search overlay — two-row structure 

The search overlay ( <mark>@id/searchOverlay</mark> in <mark>activity_reader.xml</mark> ) is now: 

- LinearLayout (vertical) ← searchOverlay `├─` Row 1: LinearLayout (horizontal, chrome keyline, actionBarSize) `│ └─` ImageButton @id/btnSearchBack         ← back arrow only 

- `├─` Row 2: EditText @id/searchEdit               ← full-width, 20dp L/R, 8dp below `│` (marginStart/End = app_row_content_padding_h, marginBottom = app_row_spacing) `└─` FrameLayout @id/searchContent               ← overlay_list (results) 

Why two rows: with a 44dp back arrow sharing a row, the box could never have equal le� and right margins, and its le� edge could never align with the results below. Splitting into a back-arrow row + a full-width box row gives the box equal 20dp le�/right margins and equal 8dp top/bottom margins (so it is symmetric on all four edges) and aligns it to the results. It also mirrors 

the SearchActivity pattern (toolbar + box below), so the two search screens look like one design. 

The IDs are unchanged, so <mark>binding.btnSearchBack</mark> , <mark>binding.searchEdit</mark> , and <mark>binding.searchContent</mark> in <mark>ReaderActivity.kt</mark> work as before. The overlay is 

inflated/hidden the same way ( <mark>binding.searchOverlay.visibility</mark> ). 

## 5. Reader bottom bar 

- <mark>@id/bottomBar</mark> now contains, top to bottom: 

1. <mark>SeekBar @id/seekChapter</mark> — the timeline seeker (20dp keyline via 

<mark>paddingStart/End = app_reader_icon_inset)</mark> . 

2. The % / Add Bookmark row — <mark>tvPercent</mark> (le�) + <mark>tvAddBookmark</mark> (right), both on the 20dp keyline, now with <mark>layout_marginTop = app_chrome_padding_v</mark> (6dp) so the row breathes the same as the icon-button rows in the top bar. 

Patch 20's removal of the prev/next chevron row is unchanged. Page-turning via tap/swipe <mark>(turnPage(forward)</mark> ) and the persistent <mark>tvPageIndicator</mark> "page X/Y" are intact. 

## 6. Centering overlay titles (the spacer trick) 

Any overlay header that has a back button on the le� and a centered title needs a matching invisible spacer on the right, or the title looks shi�ed. The TOC/Bookmarks header does this: 

<ImageButton android:id="@+id/btnOverlayBack" .../> <!-- 44dp, left --> <TextView android:id="@+id/tvOverlayTitle" android:layout_width="0dp" android:layout_weight="1" android:gravity="center" .../> <Space android:layout_width="@dimen/app_icon_button_size" android:layout_height="@dimen/app_icon_button_size" /> <!-- 44dp, right --> 

When you add a new overlay with a centered title, always include the matching right <mark>Space</mark> (sized to the back button, <mark>app_icon_button_size</mark> ). For a start-aligned title (like the search box row), no spacer is needed. 

## 7. Build & toolchain (unchanged) 

|Component|Version|
|---|---|
|Android Gradle Plugin|8.7.2|
|Kotlin|2.0.20|
|KSP|2.0.20-1.0.25|
|Gradle(wrapper)|8.9|



|Component|Version|
|---|---|
|JDK|17 (Temurin)|
|compileSdk/targetSdk|35|
|minSdk|24|



## Local build 

export JAVA_HOME=/path/to/jdk-17 export ANDROID_HOME=/path/to/android-sdk 

$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \ 

- "platform-tools" "platforms;android-35" "build-tools;35.0.0" 

./scripts/build.sh          # debug APK  -> app/build/outputs/apk/debug/the-livre-mag ./scripts/check_syntax.sh   # fast Kotlin compile gate (no APK) 

./scripts/validate.sh       # clean + compile + unit tests + APK + permission check 

./scripts/release.sh        # release APK -> release/the-livre-magicae-release.apk 

## CI (GitHub Actions) 

- <mark>android-ci.yml</mark> — push/PR. JDK 17 + Gradle, builds debug APK, uploads it, 

- asserts no INTERNET permission. Does not run unit tests (stays green). 

- <mark>release.yml</mark> — tag-triggered <mark>(v1.21)</mark> . Builds release APK, runs 

- <mark>testDebugUnitTest,</mark> creates a GitHub Release with the APK. (See Section 10 

- re: the sample-EPUB test caveat.) 

## 8. Bumping the version for the next patch 

In <mark>app/build.gradle.kts:</mark> 

versionCode = 23 // increment by 1 each release versionName = "1.22-patch22" // or your naming scheme 

<mark>versionCode</mark> must always increase. <mark>versionName</mark> is the human-readable string. 

## 9. How to add a new screen or row and keep it on the 20dp keyline 

1. Decide the edge in <mark>dimens.xml</mark> . For content that touches the screen edge, aim for the 20dp keyline. Use <mark>app_row_content_padding_h</mark> (20dp) for a full-width row's own padding, or <mark>app_grid_edge_h</mark> (10dp) for a card margin when the item is inside the 10dp main RecyclerView (10 + 10 = 20). 

2. Edit the layout XML with <mark>@dimen/...</mark> references, never literal dp for edges. Keep <mark>paddingStart == paddingEnd</mark> (and <mark>marginStart == marginEnd)</mark> so every element is symmetric le�/right. 

3. If the row lives in the main RecyclerView (which has 10dp edge padding), 

   - remember the row's own edge adds to that — use 10dp <mark>(app_grid_edge_h</mark> ) for the row's own start/end so the total is 20dp. This is the double-padding mistake Patch 21 fixed. 

4. If you remove a view with an <mark>@+id,</mark> remove every Kotlin reference too. View binding makes a dangling ID a compile error, so the build catches it. Run <mark>./scripts/check_syntax.sh</mark> a�er layout edits. 

5. Build the APK <mark>(./scripts/build.sh)</mark> and install to verify visually. 

6. Bump the version (Section 8). 

## 10. Known items to be aware of 

- Unit tests need a real sample EPUB (and the release workflow runs them). <mark>EpubParserTest</mark> reads from the hardcoded path 

- <mark>/home/user/workspace/sample.epub</mark> . The bundled test resource 

- <mark>app/src/test/resources/epub/Pride and Prejudice.epub</mark> is a truncated stub and will fail with <mark>ZipException. android-ci.yml</mark> does not run unit tests, so pushes/PRs stay green; <mark>release.yml</mark> does run <mark>testDebugUnitTest,</mark> so a tag-triggered release fails until a valid <mark>sample.epub</mark> exists at that path or the test path is adjusted. Pre-existing; unaffected by Patch 21. 

- APK output filename. <mark>app/build.gradle.kts</mark> renames every variant output to <mark>the-livre-magicae.apk.</mark> CI upload, <mark>validate.sh, build.sh</mark> , 

- <mark>release.yml, release.sh,</mark> and <mark>README.md</mark> all expect that exact name. Keep them in sync if you ever rename it. 

- Unused layouts. <mark>bottom_sheet_list.xml, bottom_sheet_search.xml</mark> , 

- <mark>dialog_search.xml, dialog_sort.xml, dialog_text_input.xml,</mark> 

- <mark>dialog_book_details.xml</mark> , <mark>item_sort_option.xml,</mark> and <mark>item_section_header.xml</mark> are not currently inflated by any adapter/activity (sort uses an AlertDialog, for example). They are le� in place for potential reuse; Patch 21 aligned <mark>item_section_header.xml</mark> to the 20dp keyline for consistency, but the others still hold older hardcoded values. If you wire one up, align its edges to 20dp first using the pattern in Section 9. 

## 11. Patch 21 file map (quick reference) 

Changed source files (vs Patch 20): 

app/build.gradle.kts                                          # version bump -> 22 / app/src/main/res/layout/item_author_series.xml               # padding 20 -> 10dp (20 app/src/main/res/layout/item_book_grid.xml                   # margin 6 -> 10dp (20dp app/src/main/res/layout/item_section_header.xml              # padding 4 -> 10dp (20d app/src/main/res/layout/activity_search.xml                  # box 20dp + marginTop; app/src/main/res/layout/activity_reader.xml                  # search overlay two-row 

Build output: <mark>app/build/outputs/apk/debug/the-livre-magicae.apk</mark> 

