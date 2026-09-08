# The Livre Magicae v29 — reader behavior, system bars, and background metadata refresh

## Reader page numbers and seeker

v29 keeps the original reader page-number and seeker behavior from the pre-v28 reader implementation. The reader uses its existing offscreen WebView measurement pass to calculate per-chapter page counts in the background. While measurement is incomplete, the reader shows `…`; when measurement finishes it shows the familiar whole-book page display.

The seeker remains chapter-based until the original per-page map is fully measured. Once measurement completes, its existing per-page mapping behavior is used. This deliberately avoids the v29 screen-page cache/exact-page seeker experiment, which caused regressions in real-device behavior.

Reader settings still invalidate the measured page counts and trigger a new background measurement because font, size, line height, margins, alignment, and related layout settings can change pagination.

The v29 Room schema retains the nullable screen-page cache columns added in the earlier v29 build so an already-installed v29 database can migrate safely. They are not used by the active reader page/seeker implementation.

## Reading-progress persistence

The reader keeps the existing reading-position model but debounces Room progress writes. WebView polling updates the UI immediately, while database writes are delayed until the position changes materially or the reader is leaving the foreground.

## Metadata refresh

Refresh Metadata runs as a tracked background job just like the folder scan. It does not keep a spinner visible or restrict navigation. A short 250 ms scanning-state pulse provides the same immediate visual feedback as Scan Now, after which the spinner disappears while the job continues. Completion is reported by snackbar, with the existing Show action opening the report.

## Metadata refresh report screen

The report screen applies system-bar insets to its content so the toolbar/title/back button and the final RecyclerView rows remain visible instead of being clipped under edge-to-edge system-bar areas.

## System bars

`SystemBarController` remains the single app-wide source of truth for black status/navigation bars. It is applied to the main library, Book Details, Search, Reader, Reader Settings, and Metadata Refresh screens. Reader-specific content/theme colors do not change the phone system-bar policy.

## TOC highlight alignment

The current TOC entry highlight is inset to the same 20dp app content keyline used by the reader's other settings lists. The existing current-entry highlighting and automatic scrolling behavior are preserved.
