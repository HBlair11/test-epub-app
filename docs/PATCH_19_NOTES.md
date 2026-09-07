# — Patch 19 The Livre Magicae 

Version: versionCode 20 / <mark>1.20-patch19</mark> 

Built on: patch 18 source (1.19-patch18) 

Scope: two changes only — (1) undo the Patch 18 fast-scroll feature, (2) reader chrome symmetry on a single 20dp keyline. 

## 1. Addition #1 (Fast Scroll) — UNDONE 

- You reported the fast scroll thumb was too small to grab on a phone and interfered with the list / scan FAB. It's been fully removed in patch 19: 

- <mark>activity_main.xml</mark> — deleted <mark>app:fastScrollEnabled</mark> and all four <mark>fastScroll*ThumbDrawable</mark> / <mark>fastScroll*TrackDrawable</mark> attributes from the 

- book RecyclerView. Normal fling scrolling is unchanged. 

- Deleted drawables: <mark>fast_scroll_thumb.xml</mark> , <mark>fast_scroll_thumb_shape.xml</mark> , <mark>fast_scroll_track.xml</mark> . 

- Removed the <mark>fast_scroll_thumb</mark> color from <mark>colors.xml</mark> . 

No Kotlin code was involved (the feature was XML-only), so there is nothing else to revert. Verified: <mark>grep -R "fast_scroll\|fastScroll"</mark> returns nothing. 

## 2. Addition #2 — Reader chrome symmetry on a 20dp keyline 

The root cause of the misalignment: the reader top/bottom bars and overlays are a custom LinearLayout (not a MaterialToolbar). Icon buttons are 44dp with a 24dp centered icon, so the visible glyph sits at <mark>container_padding + 10dp.</mark> The title/author text was padded only 6dp, so it landed at a different x than the back arrow glyph — that's the "title closer to the edge than the back button" you saw. 

## The fix: one 20dp visible keyline everywhere 

Everything in the reader chrome now aligns to a single 20dp keyline — the same keyline the Reader Settings menu content already uses (which is why that screen looked "amazingly aligned"). 

chrome edge padding (10dp) +  icon inset (10dp) = 20dp visible keyline 

- <mark>app_chrome_padding_h:</mark> 8dp → 10dp (reader chrome container edge). 

- new <mark>app_reader_icon_inset</mark> = 10dp (= (44-24)/2, the icon's inset inside a 44dp button). 

What lands on 20dp (le� and right, symmetric) 

|Element|How|Result|
|---|---|---|
|Top bar back/toc/bookmark/<br>search/settings icons|44dp button in10dp container|glyph at20dp|
|Title/author/section text|paddingStart/End=<br>app_reader_icon_inset|text at20dp,aligned with the<br>back glyph|
|Bottom bar prev/next arrows|bumped40dp→ 44dp(so inset is10dp)|glyph at20dp|
|Timeline SeekBar|paddingStart/End=<br>app_reader_icon_inset|track20dp→screen−20dp|
|%read|paddingStart=<br>app_reader_icon_inset|starts at20dp|
|Add Bookmark|paddingEnd=<br>app_reader_icon_inset|ends at20dp|
|Search box(right edge)|layout_marginEnd=<br>app_reader_icon_inset|box right at20dp=back<br>glyph|
|Search results/TOC rows|already<br>app_row_content_padding_h<br>(20dp)|text at20dp|
|Contents title|paddingEnd 50dp hack→<br>app_reader_icon_inset|spans to20dp from right|
|Contents/Bookmarks tab group|app_row_content_padding_h (20dp)|symmetric,aligned with<br>back arrow|



## Per-screen summary of what changed 

- Top bar: title/author/section now start at the same x as the back arrow and end at the same x as the settings button. Le� and right margins are equal. 

- Bottom bar: % read (le�) and Add Bookmark (right) now line up with the start/end of the timeline seeker and the prev/next arrow glyphs. 

- Search overlay: the space before the back arrow equals the space a�er the search box (both 20dp). Search results sit within the same 20dp margin and line up with the back button and search box. 

- Contents / Bookmarks overlay: the two tab buttons are symmetric (20dp each side) and in line with the back arrow; the title now spans to the 20dp right margin (the old 50dp <mark>paddingEnd</mark> hack that was eating title width is gone). 

- Reader Settings menu: unchanged — it was already the gold standard this patch aligns the rest of the chrome to. 

## How to tune the keyline 

All of it is driven by two values in <mark>res/values/dimens.xml:</mark> 

- <mark>app_chrome_padding_h</mark> (10dp) — the chrome edge padding. Bump this to push the whole chrome inward; lower it to bring buttons closer to the screen edge. 

- <mark>app_reader_icon_inset</mark> (10dp) — how far the icon glyph sits inside its 44dp button. Text/seeker/list content use this to align with the glyph. If you ever change the icon-button size (44dp) or icon size (24dp), update this to <mark>(buttonSize - iconSize) / 2</mark> so text stays aligned with the glyphs. 

Everything else references these, so you change the keyline in one place. 

## Build & verify 

- Build: <mark>./gradlew assembleDebug</mark> (JDK 17, AGP 8.7.2, Kotlin 2.0.20, API 35). 

- APK: versionCode 20, <mark>1.20-patch19</mark> , no <mark>INTERNET</mark> permission, EPUB <mark>ACTION_VIEW</mark> intent filter still present (carried from patch 18). 

- <mark>grep -R "fast_scroll\|fastScroll" app/src/main</mark> → no matches (fast scroll gone). 

- Keyline math verified: chrome edge 10dp + icon inset 10dp = 20dp visible; title text, seeker, %, bookmark, search box, tabs, and list rows all at 20dp. 

- Fix #1 (arrow icons synced): the chevrons were thin-line strokes that looked out of place next to the other reader icons (search, settings, toc, bookmark), which are all solid filled Material icons. An earlier attempt to enlarge the thin-line chevrons made them look "weird." Replaced all three arrow drawables with the official Material Design solid-filled glyphs: 

- <mark>arrow_back</mark> (back, with stem), <mark>chevron_left</mark> (prev), <mark>chevron_right</mark> (next). Now every arrow in the app is a standard solid Material icon, matching the style of the rest of the chrome. 

## Files changed in patch 19 

- <mark>app/build.gradle.kts</mark> — versionCode 19→20, versionName → <mark>1.20-patch19.</mark> 

- <mark>app/src/main/res/values/dimens.xml</mark> — <mark>app_chrome_padding_h</mark> 8dp→10dp; added <mark>app_reader_icon_inset</mark> (10dp). 

- <mark>app/src/main/res/layout/activity_reader.xml</mark> — top-bar title/author/section rows → <mark>app_reader_icon_inset;</mark> SeekBar / % / Add Bookmark → <mark>app_reader_icon_inset;</mark> prev/next buttons 40dp→44dp; search <mark>EditText marginEnd;</mark> contents title <mark>paddingEnd</mark> 50dp <mark>→app_reader_icon_inset;</mark> tab group → <mark>app_row_content_padding_h.</mark> 

- <mark>app/src/main/res/layout/activity_main.xml</mark> — removed the 5 <mark>fastScroll*</mark> attrs. 

- <mark>app/src/main/res/drawable/ic_chevron_left.xml, ic_chevron_right.xml</mark> , <mark>ic_arrow_back.xml</mark> — replaced the custom thin-line chevrons with the official Material Design solid-filled glyphs <mark>(chevron_left/chevron_right/arrow_back</mark> ), matching the style of the other solid reader icons. 

## Unchanged / carried from patch 18 

- EPUB-only picker + <mark>ACTION_VIEW</mark> intent filter + <mark>handleViewIntent</mark> (Addition #3). 

- Thin-line icon sync for <mark>ic_chevron_left</mark> / <mark>ic_chevron_right</mark> / <mark>ic_arrow_back</mark> (transparent fill) (Fix #1). 

- <mark>BookFileTypes.kt</mark> single source of truth for accepted file types. 
