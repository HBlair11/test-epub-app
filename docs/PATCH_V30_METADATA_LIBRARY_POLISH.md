# v30 — Metadata & Library Polish

This update expands local book metadata, improves cover handling, adds editable book details, and improves the empty-library experience.

## Updated / added

- `app/src/main/java/com/epubreader/app/data/BookEntity.kt` — added publish year, subject tags, and a metadata-edited guard.
- `app/src/main/java/com/epubreader/app/data/BookDao.kt` — added migration-safe metadata update paths and user-edit protection.
- `app/src/main/java/com/epubreader/app/data/AppDatabase.kt` — Room v7→v8 migration.
- `app/src/main/java/com/epubreader/app/epub/EpubModels.kt` — subject metadata support.
- `app/src/main/java/com/epubreader/app/epub/EpubParser.kt` — parses EPUB subject metadata.
- `app/src/main/java/com/epubreader/app/epub/EpubImporter.kt` — richer metadata import, generated fallback covers, and preservation of user edits during rescans.
- `app/src/main/java/com/epubreader/app/epub/CoverGenerator.kt` — new local placeholder-cover generator.
- `app/src/main/java/com/epubreader/app/BookDetailsActivity.kt` — edit metadata and cover actions.
- `app/src/main/res/layout/dialog_book_edit.xml` — new book metadata editor dialog.
- `app/src/main/res/layout/activity_book_details.xml` — richer metadata presentation and edit action.
- `app/src/main/res/values/strings.xml` — v30 strings.

No network permission or destructive Room migration was introduced.
