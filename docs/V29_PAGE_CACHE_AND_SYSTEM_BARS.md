# The Livre Magicae v29 — screen-page caching, system bars, and background refresh

## Screen-accurate pages

The reader now uses the actual paginated WebView page index for the whole-book page indicator and timeline. It no longer presents chapter numbers as a substitute for pages.

On first open, or after a layout-affecting reader setting changes, the reader shows `…` while an offscreen measurement WebView calculates the real page count. The current chapter is measured first, followed by nearby chapters, then the remaining chapters. When the full map is complete, it is cached in Room.

The cache is keyed by the EPUB content checksum plus a reader-layout fingerprint containing viewport dimensions, density, font, font size, line height, margins, alignment, hyphenation, top/bottom margin, and a pagination algorithm version. A matching cache makes the exact `current page / total pages` and per-page seeker available immediately on the next open.

Changing a layout-affecting setting invalidates the active map and triggers a new background measurement. The existing reading position (spine + scroll ratio) is preserved.

## Metadata refresh

Metadata Refresh runs as a tracked background job without activating the library SwipeRefresh spinner. Users can leave the Folders screen and continue using the app. Completion is reported with the existing snackbar/report flow.

## System bars

All activities use the same black system-bar policy. A small system-bar overlay handles Android 15 edge-to-edge behavior while leaving the existing screen layouts and reader themes unchanged. Reader themes still affect only the EPUB content area.

## Room

The database is version 7. Migration 6 -> 7 adds nullable screen-page cache fields. Existing reading/library state is preserved.

## Exact timeline behavior

The timeline now maps an absolute position directly to the measured screen-page index. A drag to page 299 resolves to the 299th measured screen page, including across chapter boundaries. Cross-chapter seeks carry the exact target page into the newly loaded WebView instead of using only a proportional scroll ratio.
