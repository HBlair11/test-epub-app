# v32 — EPUB Text-Selection & Locator Foundation

This update establishes the first reusable selection/locator infrastructure for future Define, Search, Highlight, Note, and related tools without adding those features yet.

## Updated / added

- `app/src/main/java/com/epubreader/app/epub/ReaderSelectionLocator.kt` — stable selection data model carrying text, spine href, DOM paths, offsets, and context.
- `app/src/main/java/com/epubreader/app/epub/ReaderSelectionBridge.kt` — narrow JS bridge for delivering selection data to Kotlin.
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — enables native selection, captures selected ranges, and exposes a temporary “Use selection” action.
- `app/src/main/res/values/strings.xml` — selection foundation feedback strings.

The bridge is intentionally narrow: EPUB JavaScript cannot mutate files or Room data. Full highlights, dictionary, and search actions remain future updates.
