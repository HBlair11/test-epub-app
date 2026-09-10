# v35 — Stable-baseline recovery patch

This v35 is rebuilt directly from the user-confirmed stable `the-livre-magicae-v32-home-hero-shelf-polish.zip`. The existing `.github/workflows/` directory and all three workflows are preserved unchanged.

## Requested changes

### Home — Recently Added
- Uses a dedicated Room query ordered by `books.id DESC LIMIT 6` so the shelf reflects true library insertion order.
- Newest library insertion appears first.
- Existing Library sorting remains unchanged.

### Home — Continue Reading
- Book open state is marked immediately in MainActivity, BookDetailsActivity, SearchActivity, and ReaderActivity.
- `last_opened_date` and `is_currently_reading` update immediately so Home's Room Flow can refresh the Continue Reading card without a manual refresh.

### Home — Location
- The old synthetic `Chapter X of Y` Home presentation is removed.
- Home displays the current embedded EPUB navigation TOC heading as `Location - <heading>`.
- TOC hrefs are normalized for fragments/query strings before matching to spine items.

### Home cards
- Existing 120dp × 240dp Home card dimensions are preserved.
- Title and author are each a fixed one-line field with end ellipsis.
- No card-height expansion for long metadata.

### Selection foundation
- Existing `ReaderSelectionLocator` / `ReaderSelectionBridge` foundation from the stable baseline is preserved.
- No new filesystem, Room mutation, import, delete, or settings capabilities are exposed to EPUB JavaScript.

### Offline Dictionary
- Native WebView selection menu includes `Define`.
- Single-word selection is looked up locally using bundled `assets/dict/en.db`.
- Exact match is attempted before a small inflection/base-form fallback.
- Lookup runs on `Dispatchers.IO`.
- Results are shown in a compact Material bottom sheet.
- No online fallback and no `INTERNET` permission.

## Database compatibility

Room uses schema version 12 so the app can open both the stable v32 schema (9) and the previously tested v35 schema (12) without a downgrade. The chapter metadata columns and compatibility `highlights` table are retained only to preserve existing databases; this recovery patch does not add Highlights/Notes UI.

## Version

- `versionCode = 35`
- `versionName = 1.34`

## Validation

- `.github/workflows/android-ci.yml`, `android-tests.yml`, and `release.yml` preserved byte-for-byte from the supplied stable baseline.
- Reader theme registry and palette preserved byte-for-byte from the supplied stable baseline.
- XML resources parse successfully.
- Kotlin/R/resource reference audit has no unresolved app-resource references after excluding `android.R` platform resources.
- No stale `EpubChapterDetector`, `homeContinueChapter`, `home_continue_chapter`, `customSelectionActionModeCallback`, or Activity `onTaskRemoved` references.
- All `.kt`, `.kts`, `.xml`, and `.md` files end with a newline.
- Dictionary SQLite asset opens and contains the expected `words` table.
- Room 9→10→11→12 migration SQL was simulated successfully.
- ZIP integrity verified after packaging.

The repository's GitHub Actions workflow remains the authoritative Android compile/install test.
