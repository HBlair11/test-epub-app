# v32 — Dedicated Home & Cold-Relaunch Reading Experience

## What changed

This v32 update turns **Home** into a dedicated, calm reading-oriented surface while preserving **Library** as the complete, sortable collection.

### Home
- Added a new `ShelfView.Home` inside the existing `MainActivity` navigation model.
- Added a Home drawer entry using a dedicated 24dp home vector icon.
- Home shows a restrained set of curated shelves:
  - Continue Reading — the actually most recently opened book.
  - Recently Added — latest six books by import date.
  - Favorites — latest six favorited books by recent reading activity.
  - Top Authors — up to three authors with at least two books, ranked by library count, with up to four books each.
  - Top Series — up to three series with at least two books, ranked by library count, with up to four books each.
- Home reuses the app's established book-cover/card visual language through a dedicated compact horizontal shelf adapter.
- Home intentionally has no sort control, grid/list toggle, bulk management, or dense library controls.
- An empty Home state provides a direct EPUB import action.

### Library
- The existing full Library remains the complete collection and keeps its existing grid/list, sorting, searching, scanning, and book-management behavior.
- The earlier Continue Reading card is no longer injected into the Library surface.

### Relaunch behavior
- A brand-new `MainActivity` session starts at Home.
- The current top-level shelf/detail is saved in `onSaveInstanceState()` so Android can recreate the existing screen when it kills the process but leaves the task in Recents.
- When the task is removed from Recents, the app records Home as the next cold-session entry point.
- `onTaskRemoved()` does not alter the current screen while the task remains alive.

## Files added

- `app/src/main/java/com/epubreader/app/ui/HomeModels.kt`
- `app/src/main/java/com/epubreader/app/ui/HomeBookAdapter.kt`
- `app/src/main/res/layout/view_home.xml`
- `app/src/main/res/layout/item_home_book.xml`
- `app/src/main/res/drawable/ic_home.xml`
- `docs/PATCH_V32_HOME_RELAUNCH.md`

## Files updated

- `app/src/main/java/com/epubreader/app/MainActivity.kt`
- `app/src/main/java/com/epubreader/app/ui/BookshelfViewModel.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/strings.xml`

## Architecture decision

The Home feature is implemented as another `ShelfView` within the existing `MainActivity` rather than introducing a second navigation architecture or replacing the current library/reader structure.

No changes were made to the EPUB reader architecture, WebView/Caesura pagination system, Room schema, or reader locator foundation.

## QA checklist

- [ ] Cold launch opens Home.
- [ ] Home appears first in the drawer and highlights correctly.
- [ ] Continue Reading opens the actual most recently opened book.
- [ ] Recently Added contains the latest imports without Library sorting controls.
- [ ] Favorites hides cleanly when there are no favorites.
- [ ] Top Authors hides when fewer than two authors have multiple books.
- [ ] Top Series hides when fewer than two series have multiple books.
- [ ] Tapping any Home book opens the normal Reader path.
- [ ] Long-press still opens the existing book options.
- [ ] Drawer → Library still shows the complete library with existing sorting/view controls.
- [ ] Returning from Reader to Home does not require new navigation infrastructure.
- [ ] Removing the task from Recents and launching again starts at Home.
- [ ] Keeping the app in Recents and returning to it preserves the existing current screen.
- [ ] GitHub Actions `compileDebugKotlin` passes.
- [ ] GitHub Actions `mergeDebugResources` passes.
- [ ] GitHub Actions `assembleDebug` passes.
- [ ] APK still contains no `INTERNET` permission.

## Version

- `versionCode = 32`
- `versionName` intentionally unchanged from the existing v32 baseline.


## Compile-fix follow-up: MainActivity binding/lifecycle integration

- Restored `@+id/recycler` to `activity_main.xml` because the existing MainActivity implementation uses `ActivityMainBinding.recycler` for all non-Home shelves, scroll restoration, swipe handling, and list rendering.
- Removed the invalid `MainActivity.onTaskRemoved()` override. `onTaskRemoved()` is a `Service` lifecycle callback, not an `Activity` override. The desired cold-launch behavior is instead provided by `BookshelfViewModel.initialView() = Home`, while `onSaveInstanceState()` / restoration preserves the current screen when Android recreates an existing task.
- No new navigation architecture introduced.
## V32 Home hero and shelf polish

- Continue Reading is now a dedicated visual hero with an external section heading.
- The hero includes title, author, optional series/series number, chapter position, progress, and a single read action without adding dense controls.
- EPUB spine count is stored locally so the hero can show `Chapter X of Y` after a book has been opened. Existing books are backfilled the next time they are opened.
- Home shelf cards use fixed dimensions with two-line title/author limits and ellipsis so long metadata cannot change card height or clip nearby content.
- Home horizontal shelves have increased vertical breathing room and bottom inset for a calmer, curated presentation.
- Existing 20dp content padding, Library behavior, reader architecture, and drawer navigation remain unchanged.
- Room migration 8 -> 9 adds `spine_count` with a non-destructive default of 0.

