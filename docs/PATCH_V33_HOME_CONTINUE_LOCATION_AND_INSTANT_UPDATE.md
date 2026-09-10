# v34 — Home Continue Reading location + instant update correction

## Purpose

This corrective release fixes the v33 compile errors and hardens the Home Continue Reading update path.

## Fixes

- Added the missing `colorToHex()` and `themeColor()` helpers to `ReaderActivity`.
- Home Continue Reading displays the nearest embedded EPUB navigation/TOC heading, such as `Chapter Five`, `Epilogue`, or a named section. It does not use synthetic chapter counting.
- `current_location` is stored locally, updated when the reader changes spine chapters, and preserved when an existing EPUB is rescanned/refreshed.
- Opening a book now optimistically moves that book to Continue Reading before navigation.
- Returning to Home performs a fresh Room projection so a newly opened book cannot remain hidden behind a stale Home snapshot.
- The existing Currently Reading logic remains unchanged.
- Highlights/Notes, Offline Dictionary, metadata/library polish, Home/Library separation, Recently Added ordering, selection foundation, drawer alignment, and legacy database compatibility are preserved.

## Version

- `versionCode = 34`
- `versionName = 1.33`
- Room database remains at version 12; no additional schema migration is required for this corrective release.

## Validation

- Static source/reference validation performed before packaging.
- XML resources parsed and resource IDs checked.
- Kotlin/XML/Markdown EOF newline check performed.
- Archive integrity checked.
- Full Android Gradle compilation remains dependent on the GitHub Actions environment because the local environment cannot fetch Gradle 8.9.
