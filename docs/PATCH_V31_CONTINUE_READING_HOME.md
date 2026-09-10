# v31 — Continue Reading & Home

This update makes the existing Library act as the immediate reading home without introducing a new navigation architecture.

## Updated / added

- `app/src/main/java/com/epubreader/app/data/BookDao.kt` — added a local `lastOpened` query.
- `app/src/main/java/com/epubreader/app/ui/BookshelfViewModel.kt` — exposes the most recently opened book.
- `app/src/main/java/com/epubreader/app/MainActivity.kt` — binds a Continue Reading card and a useful empty-library action while preserving existing shelves/navigation.
- `app/src/main/res/layout/activity_main.xml` — added the Continue Reading home card and empty-state action area.
- `app/src/main/res/values/strings.xml` — v31 strings.

The feature remains fully local and uses the existing reading-position state; no new reader architecture was introduced.
