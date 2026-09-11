# Patch v36 — Home Refinements, Dictionary, About/Privacy, Reading Stats, Highlights & Notes

**Version:** v36 (unchanged)
**Date:** September 2026

## Summary

This patch refines the Home screen, completes the offline dictionary feature, polishes the About/Privacy and Reading Stats screens, moves Reading Stats to the nav drawer, and introduces Highlights & Notes as the first reader annotation feature.

---

## Features

### 1. Home → Recently Added (Sort Fix)

**Problem:** The Home screen's Recently Added section was not displaying books in the correct order. The `applySort` function used a sort-then-reverse approach that caused the secondary tie-breaker (`sortTitle`) to reverse direction along with the primary key (`addedDate`), producing inconsistent ordering.

**Fix:** Updated `applySort` in `BookshelfViewModel.kt` to use explicit ascending/descending comparators for `RECENTLY_ADDED`:
- Descending (default): `compareByDescending { addedDate }.thenByDescending { id }` — newest first, with stable id tie-break
- Ascending: `compareBy { addedDate }.thenBy { id }` — oldest first, with stable id tie-break

This matches the `HomeOrderingTest` expectation and the `observeHomeRecentlyAdded` DAO query (`ORDER BY id DESC`).

**Files updated:**
- `app/src/main/java/com/epubreader/app/ui/BookshelfViewModel.kt`
- `app/src/test/java/com/epubreader/app/HomeOrderingTest.kt`

### 2. Home → Continue Reading (Re-render Fix)

**Problem:** When a user opened a book from a view other than Home (e.g., Library), the Continue Reading section on Home didn't update because `renderHome()` returned early when the current view wasn't Home. The LiveData had already emitted the updated content while Home was hidden, and didn't re-emit when the user navigated back.

**Fix:** Added a `lastHomeContent` cache field in `MainActivity`. `renderHome()` now stores the content before checking the view. `applyView()` calls `renderHome(lastHomeContent)` when entering Home, ensuring the latest DB state is always displayed.

**Files updated:**
- `app/src/main/java/com/epubreader/app/MainActivity.kt`

### 3. Offline Dictionary (Discoverability Fix)

**Problem:** The "Define" action was hidden in the text selection overflow menu (`SHOW_AS_ACTION_NEVER`), making it nearly impossible to discover. Users only saw the "Selection captured" snackbar.

**Fix:**
- Changed the "Define" menu item to `SHOW_AS_ACTION_IF_ROOM` so it appears directly in the selection toolbar
- Reordered Define to appear before "Use selection" (primary action first)
- The dictionary database (177 entries) and lookup logic (exact match → case-insensitive/base-form fallback) were already implemented and remain unchanged
- Attribution is shown in the About screen's dictionary card

**Files updated:**
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt`

### 4. About / Privacy (Layout Refinement)

**Changes:**
- Moved About below the Screen On toggle in Settings (was above it)
- Redesigned `activity_about_privacy.xml` with Material card-based layout matching the app's visual language:
  - App identity card (name, version, description)
  - Privacy card with icon header
  - Dictionary attribution card with icon header
  - Proper toolbar-style header with back button using app-wide dimensions and drawables

**Files updated:**
- `app/src/main/java/com/epubreader/app/MainActivity.kt` (Settings order)
- `app/src/main/java/com/epubreader/app/AboutPrivacyActivity.kt`
- `app/src/main/res/layout/activity_about_privacy.xml`
- `app/src/main/res/values/strings.xml` (new about strings)

### 5. Reading Stats (Nav Drawer + Layout Refinement)

**Changes:**
- Moved Reading Stats from Settings to the navigation drawer (accessible as a dedicated drawer item between Collections and Folders)
- Removed the Reading Stats row from the Settings screen
- Added `launchActivity` field to `DrawerItem` to support activity-launching drawer items
- Redesigned `activity_reading_stats.xml` with Material card-based layout:
  - Two-column stat cards for Time This Week, Time This Year, Books Finished, Current Streak
  - Full-width cards for Chapters Advanced and Pages Advanced
  - Each card has an icon, label, and value
  - Privacy banner: "All reading stats stay on your device."

**Files updated:**
- `app/src/main/java/com/epubreader/app/MainActivity.kt` (drawer item, settings removal)
- `app/src/main/java/com/epubreader/app/ui/DrawerAdapter.kt` (launchActivity field)
- `app/src/main/java/com/epubreader/app/ReadingStatsActivity.kt`
- `app/src/main/res/layout/activity_reading_stats.xml`
- `app/src/main/res/values/strings.xml` (nav_reading_stats string)

### 6. Highlights & Notes (New Feature)

**Implementation:** LocatorV1 approach (spine href + text + offsets + context) as recommended for future CFI migration.

**Flow:**
1. Long-press text in the reader → selection action mode shows "Highlight" (alongside Define)
2. Tap "Highlight" → compact color picker bottom sheet (Yellow, Green, Blue, Purple)
3. Select a color → highlight is saved to DB and injected into the WebView as a `<mark>` element
4. Tap an existing highlight → bottom sheet showing the highlighted text with a note input field
5. Save note or delete highlight directly from the sheet

**Technical details:**
- `HighlightBridge` JS interface handles tap callbacks from `<mark>` elements
- Highlights are injected after chapter load using a TreeWalker-based text search with prefix/suffix context anchoring
- `surroundContents` with `extractContents` fallback for cross-element ranges
- CSS prevents highlights from breaking across column/page boundaries
- Color constants: Yellow (#FFEB3B), Green (#66BB6A), Blue (#42A5F5), Purple (#AB47BC), rendered at 40% opacity

**Files updated/added:**
- `app/src/main/java/com/epubreader/app/data/AppDatabase.kt` (added `highlightDao()`)
- `app/src/main/java/com/epubreader/app/data/HighlightDao.kt` (added `updateNote`)
- `app/src/main/java/com/epubreader/app/data/BookRepository.kt` (highlight methods)
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` (highlight actions, JS injection, note sheet)
- `app/src/main/res/drawable/highlight_color_yellow.xml` (new)
- `app/src/main/res/drawable/highlight_color_green.xml` (new)
- `app/src/main/res/drawable/highlight_color_blue.xml` (new)
- `app/src/main/res/drawable/highlight_color_purple.xml` (new)
- `app/src/main/res/drawable/ic_highlight.xml` (new)
- `app/src/main/res/drawable/ic_note.xml` (new)
- `app/src/main/res/drawable/ic_stats.xml` (new)
- `app/src/main/res/values/strings.xml` (highlight strings)

---

## New Drawables

| File | Purpose |
|------|---------|
| `highlight_color_yellow.xml` | Yellow color circle for highlight picker |
| `highlight_color_green.xml` | Green color circle for highlight picker |
| `highlight_color_blue.xml` | Blue color circle for highlight picker |
| `highlight_color_purple.xml` | Purple color circle for highlight picker |
| `ic_highlight.xml` | Highlight action icon |
| `ic_note.xml` | Note icon |
| `ic_stats.xml` | Stats icon for Reading Stats cards |

---

## Architecture Notes

- No changes to app architecture or foundation
- Database version remains 13 (highlights table already existed from migration 10→11)
- `highlightDao()` added to `AppDatabase` (was missing despite entity/DAO existing)
- All new features use existing patterns (BottomSheetDialog, MaterialCardView, etc.)
- App version remains v36 (versionCode 36, versionName "1.35")

---

## Testing Notes

- `HomeOrderingTest` updated to test both ascending and descending Recently Added sort
- Sort logic now uses explicit comparators instead of sort-then-reverse for `RECENTLY_ADDED`
- Highlight injection is defensive (try/catch on `surroundContents` with `extractContents` fallback)
- All DB operations run on `Dispatchers.IO`
- JS interface methods are `@JavascriptInterface` annotated
