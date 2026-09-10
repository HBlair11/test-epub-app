# The Livre Magicae — v33 Patch Notes

## Scope

This v33 build is based directly on the last stable v32 Home Hero & Shelf Polish project supplied for the update. It intentionally avoids changes to the reader theme registry, CSS theme generation, EPUB pagination, or unrelated navigation architecture.

## 1. Home — Recently Added

Home now uses a dedicated Room query:

- `ORDER BY added_date DESC, id DESC LIMIT 6`
- newest added book appears first
- older books follow
- the existing Library sort selector and temporary Recently Added screen are unchanged

Using a database query avoids reusing a generic in-memory sort for this curated Home shelf.

## 2. Home — Continue Reading

Opening a book immediately marks it as opened:

- `last_opened_date` is written at ReaderActivity startup
- `is_currently_reading` is set at the same time
- Home observes the existing Room books flow, so the most recently opened book becomes Continue Reading without waiting for progress polling

This also covers books opened from Book Details and other app entry points that launch ReaderActivity.

## 3. Continue Reading — EPUB TOC Location

The Home card no longer shows synthetic chapter totals.

Instead it displays the current location label derived from the EPUB's embedded navigation TOC, such as:

- `Location - 7. Sky-high Rivalry`
- `Location - Chapter Five`
- `Location - Epilogue`

The label is stored in `current_location` so Home does not need to parse the EPUB on every render.

## 4. Home Book Cards

The horizontal Home card remains `240dp` tall.

The cover area is slightly shorter to preserve a fixed metadata zone. Title and author each have a fixed one-line slot with end ellipsis. Longer text does not grow the card or push other content.

## 5. Selection Foundation

The existing native WebView selection/locator foundation was retained and validated.

The foundation continues to provide:

- selected text
- current spine href
- DOM/node paths
- start/end offsets
- prefix/suffix context
- renderer-independent `ReaderSelectionLocator`

The JavaScript bridge remains narrow and read-only.

No filesystem, Room, deletion, import, settings mutation, or other write operations are exposed to EPUB JavaScript.

## 6. Offline Dictionary

A local dictionary is now available from the WebView selection menu via `Define`.

Flow:

`long-press word → Define → compact bottom sheet`

Lookup behavior:

1. exact case-insensitive match
2. small local inflection/base-form fallback
3. no network fallback

The database is bundled at `assets/dict/en.db` and copied to private app storage on first use. Lookup runs on `Dispatchers.IO`.

This v33 build uses an original curated starter dictionary set rather than an external dictionary dataset, avoiding third-party dictionary licensing requirements. Attribution is shown in About.

## Database

Room is upgraded from version 9 to version 10 with:

- `current_location TEXT`

No destructive migration is used.

## Version

- Version code: **33**
- Version name: **1.32**

## Validation checklist

- XML resources parse successfully
- changed resource IDs were checked against layouts
- changed string references were checked
- Room migration chain is contiguous through 9 → 10
- `EpubChapterDetector` remains absent
- obsolete Home chapter-count strings remain absent
- no `customSelectionActionModeCallback`
- no Activity `onTaskRemoved()` override
- `binding.recycler` remains present
- no `INTERNET` permission
- source files end with newline
- packaged ZIP integrity verified

The Android Gradle compilation still needs to be confirmed by the project's GitHub Actions workflow because the local environment does not have the Gradle 8.9 distribution available.
