# v32 — Home Chapter Removal, Selection Validation, Highlights & Offline Dictionary

## Purpose

This v32 correction removes the unreliable user-facing chapter-count feature from the Home Continue Reading card. The Home card no longer displays spine-based or heuristic `Chapter X of Y` values.

The old Room `spine_count`, `chapter_count`, and `chapter_index` columns remain only because users may already have a v32 database containing them. They are retained as legacy schema fields so the database can migrate safely; no Home/reader feature code reads or writes them.

## Home card

- Continue Reading no longer shows a chapter-count line.
- Card remains 240dp tall.
- Cover area is compacted to preserve a dedicated metadata area.
- Title and author each have one fixed line with end ellipsis.
- Long metadata cannot increase card height or push other content.

## Selection foundation

- Native WebView selection is retained.
- Selection payload contains selected text, current spine href, DOM start/end paths, DOM offsets, normalized character offsets, and prefix/suffix context.
- `@JavascriptInterface` is exposed only for receiving selection data.
- No filesystem, Room, delete, import, settings, or other mutation capability is exposed through the bridge.
- `ReaderSelectionLocator` is renderer-independent and ready for downstream features.

## Highlights & notes

- Long-press selection adds `Highlight` and `Note` actions to the native selection menu.
- Highlights are stored in Room with a real `10 -> 11` migration.
- The existing Contents/Bookmarks overlay gains a Highlights tab; notes are shown with their associated highlighted text.
- Highlight locations use text plus normalized offsets and prefix/suffix context; DOM paths are retained for same-renderer precision.
- Clean chapter HTML is loaded first and the highlight runtime clears existing marks before applying the current set.
- User text is passed into JavaScript as JSON data rather than raw executable markup.

## Offline dictionary

- Long-press selection adds `Define`.
- The first implementation is English-only and local-only.
- Exact lookup is attempted first, followed by lightweight base-form fallback for common inflections and irregular forms.
- Lookup is performed from a bundled SQLite asset off the main thread.
- No network fallback is present and no `INTERNET` permission is introduced.
- The bundled seed definitions are project-authored and dedicated to CC0 1.0 Universal.
- The Settings > About dialog includes the dictionary attribution.

## Validation notes

Static validation for this patch includes XML parsing, binding/resource reference checks, Room migration-chain checks, stale chapter-detector reference checks, trailing-newline checks, manifest permission checks, and ZIP integrity. Full Gradle compilation remains delegated to the project's GitHub Actions workflow because the local environment does not have the required Gradle distribution cached.
