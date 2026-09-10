# The Livre Magicae — v32 Home Hero & Shelf Polish

## Changes

- Promoted Continue Reading into a larger hero card with a dedicated `Continue Reading` heading.
- Added optional series/series-number context and `Chapter X of Y` when the stored EPUB spine count is available.
- Added Room migration 8 -> 9 for `spine_count`; existing books are backfilled safely when opened.
- Fixed Home shelf cards to a consistent size and capped title/author text at two lines with ellipsis.
- Increased Home section spacing and horizontal shelf bottom insets to reduce crowding.
- Kept the existing 20dp app-wide/drawer content padding and Library behavior unchanged.

## Files updated

- `app/src/main/res/layout/view_home.xml`
- `app/src/main/res/layout/item_home_book.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/java/com/epubreader/app/MainActivity.kt`
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt`
- `app/src/main/java/com/epubreader/app/data/BookDao.kt`
- `app/src/main/java/com/epubreader/app/data/BookEntity.kt`
- `app/src/main/java/com/epubreader/app/data/AppDatabase.kt`
- `app/src/main/java/com/epubreader/app/epub/EpubImporter.kt`
- `docs/PATCH_V32_HOME_RELAUNCH.md`

## Version

App version remains **v32**. Room schema advances from 8 to 9 through an explicit migration; no destructive migration is used.
