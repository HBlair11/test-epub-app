# — Caesura Patch 8 Notes 

App: Caesura (Epub Reader) · <mark>com.epubreader.app</mark> Version: 1.0 (versionCode 1) · Build: Patch 8 debug 

APK: <mark>app-debug-patch8.apk</mark> 

Min Android: 7.0 (API 24) · Target: 15 (API 35) 

Patch 8 reworks the reader's page model and transitions around the same idea the ReadEra / Kindle engines use: a stable, instant "book page" count derived from the text itself, plus a snapshot overlay that masks cross-chapter blank flashes. It also adds the book's own embedded fonts and Calibre/Readium-style image handling. 

## 1. Instant, stable total page count (ADE byte-mapping) 

This is the headline change. Previously the total page count came from 

measuring every chapter in an offscreen WebView — slow (seconds for a large book), it flashed "…" while measuring, and it changed every time you changed the font. 

### Patch 8 replaces that with the Adobe Digital Editions (ADE) byte-mapping 

model: a "book page" is ~1024 bytes of visible text. 

- New pure object <mark>EpubPageMap</mark> : 

   - ` 

   - - visibleText(html)` strips tags, `<script>`/`<style>` blocks, and decodes 

common HTML entities to get just the readable text. 

   - <mark>visibleTextBytes(html)</mark> counts its UTF-8 byte length (multi-byte chars counted by bytes, not characters). 

   - <mark>toPageCount(bytes) = ceil(bytes / 1024),</mark> minimum 1 page. 

   - <mark>compute(book)</mark> reads each spine entry from the EPUB zip once and returns per-spine page counts — a fast text-only pass, no layout. 

- The map is computed once on import and cached in the database 

- <mark>(books.page_map_csv</mark> , Room migration 3 → 4, <mark>BookDao.updatePageMap)</mark> . On every reopen the cached CSV is read → the total is instant. Books imported before this patch have no cached map yet, so the first open computes + caches it. 

- The synthetic counts now drive the total, the per-page seeker, the page 

- indicator, and the % progress (see <mark>ReaderPageMapping</mark> prefix sums reused on the synthetic counts). 

- Consequence (a feature, matching Kindle/ReadEra): the total is stable 

- across font / size / margin / line-height / alignment / hyphenation changes — 

changing the font does not change the total page count. You may swipe more " than once to advance one book page," or a single swipe can advance several. 

- 

- Because the synthetic count is font/layout independent, changing reader settings no longer re-runs the slow offscreen measurement pass — <mark>applySettingsAndReload</mark> keeps the page map and only re-renders the current ' 

- chapter s CSS. The offscreen measure WebView is now bypassed. 

   - Not claimed: an exact, screen-accurate one-swipe-per-page physical page count. ADE pages are a stable estimate (like Kindle "Locations"), not a per-screen-page count. 

<mark>EpubPageMapTest</mark> covers <mark>toPageCount</mark> , <mark>toPageCounts</mark> , <mark>visibleText</mark> , and 

<mark>visibleTextBytes</mark> (including multi-byte UTF-8). Existing <mark>ReaderPageMappingTest</mark> now exercises the synthetic counts. 

## 2. Snapshot overlay for seamless cross-chapter transitions 

The old cross-chapter path hid the WebView ( <mark>alpha = 0)</mark> while the next chapter loaded, then revealed it a�er the target page was positioned — which le� a brief blank flash. 

Patch 8 adds a <mark>snapshotView ImageView</mark> layered above the reading WebView: 

- Before a cross-chapter load <mark>(loadChapter</mark> with a different spine index), the current page is captured into a bitmap <mark>(view.draw(canvas))</mark> and shown in the overlay, so the page you're leaving stays visible while the next chapter loads and is positioned. 

- Once the target page is positioned and revealed <mark>(applyPendingFragmentOrRestore</mark> ), the snapshot fades out (220 ms), giving a crossfade from old page to new. 

- Applies to all cross-chapter navigation: page-turn across a chapter boundary, TOC clicks, and seeker drags into another chapter. 

- In-chapter page turns already animated (JS <mark>animateTo,</mark> 240 ms ease) — unchanged. 

Best-effort, not a full pre-render: <mark>view.draw(canvas)</mark> on a 

hardware-accelerated WebView can capture a blank bitmap on some devices. If capture fails (or yields blank), the code falls back to the previous 

alpha-hide behavior — no regression. True adjacent-resource pre-rendering (a second visible WebView that pre-renders the next/prev chapter, like Readium's ViewPager) is future, device-tested work — see <mark>FUTURE_UPDATES.md.</mark> 

## 3. Publisher (embedded) font option 

A new Publisher font choice lets the EPUB use its own embedded fonts. 

- When selected, <mark>buildReaderCss</mark> emits no <mark>font-family</mark> override, so the book's <mark>@font-face</mark> declarations take effect. 

- <mark>EpubResourceResolver</mark> already serves font resources <mark>(.ttf</mark> / <mark>.otf</mark> / 

- <mark>.woff</mark> / <mark>.woff2</mark> ) with the correct <mark>font/*</mark> MIME types, so embedded fonts 

- render through the same virtual <mark>epub.local</mark> URL path as other resources. 

- Added <mark>Font.PUBLISHER</mark> , a <mark>btnFontPublisher</mark> toggle in the reader settings font group, and the <mark>Publisher</mark> string. 

Not claimed: a per-embedded-font picker (a dropdown of each <mark>@font-face</mark> " " " ' the book ships). Publisher means honor the book s own typeface choices." 

## 4. Calibre / Readium-style image handling 

The reader image CSS was rewritten to match how Calibre (desktop) and Readium (Android) size images inside a reflowable, paginated column layout: 

- <mark>img, svg, video</mark> : <mark>max-width: 100%</mark> , <mark>max-height: 100%</mark> , <mark>height/width: auto</mark> , <mark>object-ft: contain</mark> (preserve aspect ratio), <mark>break-inside: avoid</mark> . 

- A large image is constrained to fit within one page height so it never bleeds across a page break. 

- <mark>fgure</mark> / <mark>picture</mark> get <mark>break-inside: avoid</mark> ; <mark>table</mark> gets 

- <mark>max-width: 100%</mark> + break-inside avoid. 

- Inline icons are not forced to <mark>display: block,</mark> so they stay inline in 

- flowing text (only standalone/figure images are strongly contained). 

## What is NOT in this patch (documented future work) 

- ReadEra-style custom Canvas / OpenGL rendering — a full reader-engine 

- rewrite (custom text layout, font metrics, bidi, selection, pagination). Not done; needs a device to validate. See <mark>FUTURE_UPDATES.md</mark> . 

- Readium-style ViewPager + double-WebView adjacent pre-rendering — the snapshot overlay is the practical, lower-risk stand-in. Full pre-rendering is future/device-tested work. 

- Full EPUB CFI (Content Fragment Identifier) position memory — position is still tracked by <mark>spineIndex</mark> + within-chapter scroll ratio. The synthetic model preserves your % across font changes (stable total), but exact word-level anchoring a�er a layout change is future work. 

## Verification 

- <mark>./gradlew assembleDebug</mark> — BUILD SUCCESSFUL. 

- <mark>-- --</mark> 

- <mark>./gradlew testDebugUnitTest tests EpubPageMapTest tests ReaderPageMappingTest</mark> 

- <mark>--tests RescanDecisionTest</mark> — BUILD SUCCESSFUL (targeted Patch 8 tests pass). 

- <mark>aapt2 dump permissions</mark> — only the platform 

- <mark>DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION;</mark> no INTERNET (or any network) permission. 

Package <mark>com.epubreader.app,</mark> versionName <mark>1.0,</mark> minSdk 24, targetSdk 35. 

Files of interest 

- <mark>epub/EpubPageMap.kt</mark> — ADE byte-mapping (pure) + <mark>compute</mark> . 

- <mark>data/BookEntity.kt</mark> ( <mark>pageMapCsv</mark> ), <mark>data/AppDatabase.kt</mark> (migration 3→4), <mark>data/BookDao.kt</mark> ( <mark>updatePageMap</mark> ), <mark>epub/EpubImporter.kt</mark> (compute + cache). 

- <mark>ReaderActivity.kt</mark> — synthetic page map wiring, ratio-based 

- indicator/seeker/progress, snapshot overlay, Publisher font + image CSS. 

- <mark>res/layout/activity_reader.xml</mark> ( <mark>snapshotView</mark> ), 

- <mark>res/layout/dialog_reader_settings.xml (btnFontPublisher)</mark> , 

- <mark>ui/ReaderSettingsSheet.kt, data/PrefsManager.kt</mark> ( <mark>Font.PUBLISHER)</mark> , <mark>res/values/strings.xml.</mark> 

See <mark>FUTURE_UPDATES.md</mark> for the deferred items (full Canvas rendering, 

ViewPager/double-WebView pre-rendering, full CFI) and how to extend the reader. 
