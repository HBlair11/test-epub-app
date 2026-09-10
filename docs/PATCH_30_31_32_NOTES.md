# v30-v32 Cumulative Update Notes

This cumulative source state contains three intentionally small milestones delivered together for testing:

- **v30 — Metadata & Library Polish**: richer metadata storage/presentation, editable book details, cover fallback generation, and user-edit preservation during rescan.
- **v31 — Continue Reading & Home**: the existing Library landing view gains a quiet Continue Reading card backed by the most recently opened book. No new navigation architecture was introduced.
- **v32 — Selection Foundation**: native WebView text selection is re-enabled and a narrow JavaScript bridge captures selection text, spine href, DOM paths, offsets, and local context for future highlight/note/dictionary/search work.

The final cumulative APK/source version is **versionCode 32 / versionName 1.31**.

## Architecture preservation

The existing WebView + Caesura pagination reader remains intact. No Compose migration, universal document abstraction, or large activity rewrite was introduced.

## v30 files

- `app/src/main/java/com/epubreader/app/data/BookEntity.kt` — publish year, subject tags, `metadataEdited`.
- `app/src/main/java/com/epubreader/app/data/BookDao.kt` — metadata editing query and most-recently-opened observer.
- `app/src/main/java/com/epubreader/app/data/AppDatabase.kt` — Room migration 7->8.
- `app/src/main/java/com/epubreader/app/epub/EpubModels.kt` — subjects collection.
- `app/src/main/java/com/epubreader/app/epub/EpubParser.kt` — `dc:subject` parsing.
- `app/src/main/java/com/epubreader/app/epub/CoverGenerator.kt` — generated local fallback cover.
- `app/src/main/java/com/epubreader/app/epub/EpubImporter.kt` — fallback cover generation and preservation of user metadata/cover edits on rescan.
- `app/src/main/java/com/epubreader/app/BookDetailsActivity.kt` — edit details and choose a cover from SAF.
- `app/src/main/res/layout/dialog_book_edit.xml` — metadata edit form.
- `app/src/main/res/layout/activity_book_details.xml` — Edit Details action and richer metadata display.
- `app/src/main/res/values/strings.xml` — v30 labels/messages.

## v31 files

- `app/src/main/java/com/epubreader/app/ui/BookshelfViewModel.kt` — observes the most recently opened book.
- `app/src/main/java/com/epubreader/app/MainActivity.kt` — binds Continue Reading and keeps it scoped to the Library landing view.
- `app/src/main/res/layout/activity_main.xml` — adds the non-invasive Home/Continue Reading surface.

## v32 files

- `app/src/main/java/com/epubreader/app/epub/ReaderSelectionLocator.kt` — renderer-independent selection payload.
- `app/src/main/java/com/epubreader/app/epub/ReaderSelectionBridge.kt` — narrow selection-only JS bridge.
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — re-enables native selection and captures locator data through the bridge.
- `app/src/main/res/values/strings.xml` — selection foundation messages.

## Drawer alignment carried forward

This cumulative source also carries the latest drawer fix:

- drawer header app icon view: **24dp x 24dp**;
- drawer vector icon views: **24dp x 24dp**;
- icon-to-label gap: **16dp**;
- existing **20dp** drawer content padding remains untouched;
- drawer-only tightly cropped app artwork is stored at `res/drawable-nodpi/ic_launcher_drawer.png`.

## Validation goals

- Install the v32 APK **over** an existing v29 install containing real books.
- Confirm Room migration preserves all books and reading state.
- Edit title/author/metadata and a cover, then rescan the same source and confirm edits remain.
- Import a book with no cover and confirm a generated placeholder cover is shown.
- Confirm Library shows Continue Reading only when a recently opened book exists.
- Long-press in an EPUB and confirm native selection works; the new `Use selection` action should capture the selection without exposing filesystem/database mutation methods to EPUB JavaScript.
- Verify the cumulative build contains no new network permission.

## Additional correctness note

When a user has manually curated metadata, v30 preserves those values during parser refresh while still updating the source URI, filename, size, and modification timestamp used for import identity.
