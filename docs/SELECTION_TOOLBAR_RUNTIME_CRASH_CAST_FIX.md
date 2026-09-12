# Selection Toolbar Runtime Crash Fix

## Update

Fixed the runtime crash that occurred when a text selection opened the custom selection toolbar.

## Root cause

The `selection_toolbar_more` view was changed to an `ImageButton` so the toolbar could use the vertical three-dots icon, but `ReaderActivity.kt` was still retrieving that view as a `TextView`. Android therefore attempted an invalid `ImageButton` → `TextView` cast when the toolbar was inflated.

## Fix

`ReaderActivity.kt` now retrieves `selection_toolbar_more` as `ImageButton`. No toolbar layout, spacing, movement, or selection behavior was otherwise changed in this update.

## Files updated

- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — corrected the More-button view type.
- `docs/SELECTION_TOOLBAR_RUNTIME_CRASH_CAST_FIX.md` — this update note.
