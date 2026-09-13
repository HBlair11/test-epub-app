# Patch J — Bookmark Reflow-Safe Location

## Update

This patch builds on Patch I and keeps the existing bookmark, TTS, selection-toolbar, history/navigation, highlight, and reader Search behavior unchanged.

### Whole-page bookmark snippets
- Reworked whole-page snippet extraction to resolve the first DOM text position on the requested rendered page, then read forward from that DOM position.
- The extraction no longer rebuilds the snippet by accepting/rejecting individual rendered characters or text nodes.
- A paragraph/text node that crosses a pagination boundary is trimmed at the actual page boundary instead of including text from the previous page.
- The existing snippet display length remains unchanged.

### Bookmark locations after Reader Settings changes
- Bookmark navigation now treats the saved bookmark snippet/selected text as the primary semantic location.
- After font, font size, margins, line height, alignment, or other reader reflow changes, the app searches the chapter DOM for the saved text and resolves its new rendered page.
- The stored page number and ratio remain only as compatibility/fallback information; they are no longer the primary location for bookmarks with a saved text anchor.
- Whole-page bookmarks use their saved page-start snippet as the semantic anchor.
- Selected-text bookmarks use the exact selected text as the semantic anchor.

### Duplicate detection after reflow
- Whole-page and selected-text duplicate checks first use the semantic text anchor, so changing Reader Settings does not cause the same bookmark to be treated as a new bookmark merely because its rendered page number changed.
- Existing page/ratio checks remain as fallback compatibility checks.

### Explicitly not changed
- Reader Settings/reflow implementation itself.
- TTS.
- Selection toolbar.
- Reader navigation/history.
- Highlights.
- Reader Search.
- Bookmark delete/Undo snackbar behavior.
- Bookmark ordering.
- Percentage visibility.

## Files changed
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt`
- `app/src/main/java/com/epubreader/app/data/BookmarkDao.kt`
- `docs/PATCH_J_BOOKMARK_REFLOW_SAFE_LOCATION.md`
