# V29 Timeline Reuse Update

This V29 update keeps the existing rendered-page architecture and fixes three reader UX issues without replacing the Caesura pagination engine.

## 1. Exact total-page caching

The visible reader WebView remains the authority for rendered pages. A whole-book exact total still cannot be obtained instantaneously for a brand-new EPUB because only the currently loaded spine item has been paginated by the visible WebView. Every spine item must be laid out to know the exact book total.

To remove the repeated delay on reopen, V29 now reuses the existing Room fields `screen_page_map_csv` and `screen_page_layout_key`:

- after the first complete background measurement, the rendered page count for every spine item is stored;
- the cache is keyed by WebView width/height, density, font, font size, line height, margins, alignment, hyphenation, and page-guard settings;
- when the same EPUB is opened again with the same reader layout, the cached exact page counts are loaded immediately;
- the background measurement WebView is not started when a matching cache exists;
- a new book or a reader layout with no matching cache still requires the one-time background measurement;
- changing settings invalidates the old layout cache naturally because the layout key changes.

This keeps page totals exact instead of showing an estimated or synthetic total.

## 2. Seeker page-number race fix

The visible WebView's `gotoPage()` remains the exact navigation API. The bug was a stale asynchronous `currentPage()` poll arriving after a seeker navigation and briefly overwriting the newly selected page.

V29 now records the exact requested seeker location and treats it as authoritative until the visible WebView reports that same page. A stale poll therefore cannot flash an incorrect page such as `155 / 425` before the requested `160 / 425`.

## 3. Transparent history overlay

Reader history is now a sibling overlay above the opaque reader chrome instead of a child of the bottom bar. It has no background of its own, so the EPUB page's current reading-theme background remains visible underneath it.

The history text uses a fixed small muted-gray color across reader themes, with light horizontal breathing room. The bottom bar itself remains unchanged.

## 4. TOC highlight breathing room

The existing 20dp app-wide inset for the TOC highlight is preserved. The TOC title now has an additional 8dp start/end padding inside that highlight box, so text no longer appears visually stuck to the left edge of the highlighted surface.
