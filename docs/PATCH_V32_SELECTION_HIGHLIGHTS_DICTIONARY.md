# v32 — Selection Foundation Validation + Highlights/Notes + Offline Dictionary

## Scope

This cumulative v32 patch keeps the existing EPUB reader architecture and adds the first downstream consumers of the selection foundation: Highlights/Notes and an offline dictionary.

## Selection foundation

- Native Android WebView text selection remains the selection mechanism.
- Selection payload includes selected text, current spine href, DOM start/end paths, DOM offsets, normalized character offsets, and prefix/suffix context.
- The `LivreSelection` bridge is receive-only and does not expose filesystem, Room, import, delete, or settings mutation methods.

## Highlights and Notes

- Native selection menu actions: Highlight, Note, Define, and the existing validation action.
- Highlights and notes are stored in a dedicated Room table.
- Migration `10 -> 11` creates the table and indexes it by book and spine href.
- The existing Contents/Bookmarks overlay now has a third `Highlights` tab rather than introducing a new navigation pattern.
- Highlight records contain selected text, normalized offsets, prefix/suffix context, and DOM paths.
- Highlight rendering first clears prior highlight spans and then applies the current chapter's set, avoiding cumulative DOM wrapping.
- User/book text is transmitted into JavaScript as JSON data and is not injected as executable HTML.
- Tapping a saved highlight navigates to its spine and centers the matching highlighted text when it can be relocated.

## Offline dictionary

- `Define` is available from native text selection.
- Lookup is exact-first and then uses lightweight common inflection/base-form fallback.
- The database is bundled under `assets/dict/en.db` and copied locally on first use.
- The bundled v32 seed contains 177 project-authored English entries and is dedicated to CC0 1.0 Universal.
- Lookup runs on `Dispatchers.IO`.
- No online fallback exists and no network permission is introduced.
- Settings > About includes the dictionary attribution.

## Chapter-count removal

The prior heuristic `Chapter X of Y` feature is removed from the Home Continue Reading card. The unreliable chapter detector source and user-facing chapter-count resources are gone. Legacy Room columns from earlier v32 builds remain only to keep already-installed databases migratable and are not read by the Home UI.

## Validation performed before packaging

- All XML resources parse successfully.
- Modified Kotlin feature classes compile with Android/Room/SQLite API stubs, catching syntax and type-reference errors in the new feature classes.
- Kotlin binding references used by `MainActivity` and `ReaderActivity` resolve to IDs in their layouts.
- Direct `R.string` and `R.drawable` references in modified Kotlin resolve.
- Stale chapter-detector and removed Home chapter UI references are absent from source.
- Duplicate reader companion object, invalid `customSelectionActionModeCallback`, and invalid `onTaskRemoved` Activity override are absent.
- Room database version is 11 with an explicit 10 -> 11 migration.
- Version code remains 32.
- `INTERNET` permission remains absent.
- All `.kt`, `.kts`, `.xml`, and `.md` files end with a newline.
- Dictionary SQLite asset integrity and record count were checked.

Full Android compilation is still performed by the project's GitHub Actions workflow; this environment cannot download the project's Gradle 8.9 distribution and therefore cannot honestly report a green GitHub build from here.
