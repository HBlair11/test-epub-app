# Patch v36 — Round 2 Fixes: Selection Actions, TTS Speed, Highlights Tab, UI Refinements

## Summary

This patch addresses 10 issues reported after the first round of v36 patches. The fixes cover text selection actions (Dictionary + Highlights), TTS speed control, a Highlights tab in the TOC overlay, Home screen scroll behavior, Settings/Currently Reading menu cleanup, status bar fixes, and icon removal from Reading Stats.

## Changes by Feature

### 1. About/Privacy & Reading Stats — Status Bar + 20dp Padding
- **AboutPrivacyActivity.kt** — Added `SystemBarController.apply(this)` and WindowInsetsCompat listener for status bar padding.
- **ReadingStatsActivity.kt** — Same SystemBarController + insets treatment.
- **activity_about_privacy.xml** — Switched padding from 16dp to 20dp (`app_row_content_padding_h`), removed decorative ImageView icons, added `aboutRoot` id for insets.
- **activity_reading_stats.xml** — Switched padding to 20dp, removed ALL ImageView icons from stat cards, added `statsRootLayout` id for insets.

### 2. Home Recently Added Sort — m-time Descending
- **BookshelfViewModel.kt** — `buildHomeContent()` now sorts the Recently Added section by `sourceLastModified` (file m-time) descending, then `addedDate` descending, then `id` descending. This ensures the most recently added books appear first. The Library/shelf `applySort` for RECENTLY_ADDED still uses `id` DESC to avoid changing existing Library behavior.
- **HomeOrderingTest.kt** — Updated with tests for both Home m-time sort and Library id sort.

### 3. Dictionary/Highlights — Custom Selection Bottom Sheet
- **ReaderActivity.kt** — Added `showSelectionActionsSheet()` method that shows a BottomSheetDialog with Define and Highlight buttons when text is selected. This replaces the unreliable floating action mode approach (which didn't show custom menu items on many devices). Menu items are still added with `SHOW_AS_ACTION_ALWAYS` as a fallback. Added `onActionModeFinished` override that does NOT auto-dismiss the sheet (to prevent immediate dismissal when the sheet steals focus). Added `selectionActionsSheet` field with guard against duplicate sheets.

### 4. TTS Speed Control
- **ReaderTtsController.kt** — Added `speechRate` property (default 0.9f) with custom setter that calls `tts?.setSpeechRate(rate)`. Called in `playInternal()` before speaking.
- **activity_reader.xml** — Added `ttsSpeed` SeekBar (0-19 range, mapping to 0.5x-1.5x) and `ttsSpeedLabel` TextView to TTS controls layout.
- **ReaderActivity.kt** — Wired SeekBar listener that updates speech rate live and persists via `PrefsManager.ttsSpeedProgress`. Uses `Locale.US` for formatting to avoid locale crashes.
- **PrefsManager.kt** — Added `ttsSpeedProgress` (Int, default 8) and `KEY_TTS_SPEED` constant.

### 5. Highlights Tab in TOC/Bookmarks Overlay
- **activity_reader.xml** — Added `btnTabHighlights` MaterialButton to the overlay tab group.
- **HighlightListAdapter.kt** (new) — Simple ListAdapter showing highlight text, color dot, and optional note. Tapping a highlight navigates to its spineHref.
- **ReaderActivity.kt** — Added `highlightRv`, `highlightEmpty`, `highlightObserverStarted`, `activeOverlayTab` fields. Updated `showTocBookmarks()` to inflate and set up the highlights list. Updated `applyOverlayTab()` from 2-state to 3-state (Contents/Bookmarks/Highlights). Added highlight DB observer via `db.highlightDao().observeForBook(bookId)`.
- **strings.xml** — Added `reader_tab_highlights` and `reader_highlights_empty` strings.

### 6. Home Scroll-to-Top
- **MainActivity.kt** — Added `homeScroll.post { scrollTo(0, 0) }` in `applyView()` when entering Home. Added same-view check in `selectDrawer()`: if already on Home and user taps Home in drawer, scroll to top without full re-render.

### 7. Settings — Remove Search/Import from Toolbar
- **MainActivity.kt** — Updated `onPrepareOptionsMenu()` to hide `action_search` and `action_import` when view is `ShelfView.Settings`. Added `invalidateOptionsMenu()` call in `applyView()`.

### 8. Currently Reading — Force Recently Opened Sort
- **BookshelfViewModel.kt** — Updated `applySortFor()` to force `RECENTLY_READ` sort (descending) for `ShelfView.Reading`, ignoring session sort preferences.
- **MainActivity.kt** — Updated `onPrepareOptionsMenu()` to hide `action_sort` when view is `ShelfView.Reading`.

### 9. Reading Stats — Remove Random Icons
- **activity_reading_stats.xml** — Removed all ImageView icons from Books, Days Reading, Pages, Words, and Max Streak stat cards.

## Files Modified/Added

| File | Status |
|------|--------|
| `app/src/main/java/com/epubreader/app/AboutPrivacyActivity.kt` | Modified |
| `app/src/main/java/com/epubreader/app/ReadingStatsActivity.kt` | Modified |
| `app/src/main/java/com/epubreader/app/ReaderActivity.kt` | Modified |
| `app/src/main/java/com/epubreader/app/MainActivity.kt` | Modified |
| `app/src/main/java/com/epubreader/app/epub/ReaderTtsController.kt` | Modified |
| `app/src/main/java/com/epubreader/app/ui/BookshelfViewModel.kt` | Modified |
| `app/src/main/java/com/epubreader/app/ui/HighlightListAdapter.kt` | **New** |
| `app/src/main/java/com/epubreader/app/data/PrefsManager.kt` | Modified |
| `app/src/main/res/layout/activity_about_privacy.xml` | Modified |
| `app/src/main/res/layout/activity_reading_stats.xml` | Modified |
| `app/src/main/res/layout/activity_reader.xml` | Modified |
| `app/src/main/res/values/strings.xml` | Modified |
| `app/src/test/java/com/epubreader/app/HomeOrderingTest.kt` | Modified |
| `docs/PATCH_V36_R2_SELECTION_TTS_HIGHLIGHTS_UI.md` | **New** |
