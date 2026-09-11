# V37 UI Polish, Version Bump, and Project Review

## Reading Stats / About & Privacy restyle

Both screens now use the app-standard header convention (same as Metadata Refresh and Search): a `MaterialToolbar` at `?attr/actionBarSize` with the standard back arrow and title, replacing the custom 56dp header rows. Spacing and corners moved to the shared dimension scale — `app_card_spacing` (12dp), `app_section_spacing` (16dp), and `app_card_corner` (14dp) were added to `dimens.xml` and are used by the stats cards, the about cards, the definition card, and the vocabulary screen. The old per-screen hardcoded 12dp/14dp/16dp values were replaced.

Files: `res/layout/activity_reading_stats.xml`, `res/layout/activity_about_privacy.xml`, `ReadingStatsActivity.kt`, `AboutPrivacyActivity.kt`, `res/values/dimens.xml`.

## Version

- `versionCode` 36 → 37, `versionName` 1.35 → **1.36** (`app/build.gradle.kts`; file's CRLF line endings preserved).
- About screen version string updated to "Version 1.36".

## Project review notes

- Removed dead code surfaced by this patch: the selection-capture bottom sheet path (`selectionActionsSheet`, `SELECTION_CAPTURE_ID`, the auto-capture in `onActionModeStarted`, the no-op `onActionModeFinished`, the unused `lastSelection` field) and the now-unused `selection_capture` / `selection_captured` / `selection_single_word_required` strings.
- `ReaderSelectionLocator` gained WebView-local rect fields so contextual UI can anchor to the selection; the JS payload escapes all injected text and the bridge remains a narrow, no-mutation interface (EPUB content is still treated as untrusted).
- Dictionary queries run on `Dispatchers.IO` with a single cached `DictionaryLookup` instance (rebuilt when the book language changes); no main-thread database work was added.
- All Room changes ship as a real migration (`MIGRATION_13_14`); no destructive fallback anywhere.
- Every `.kt` / `.kts` / `.xml` file in the project ends with a trailing newline (verified).
- No files or folders were removed from the project; the zip contains the complete source tree.
