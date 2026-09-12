# Selection ActionMode Fix

## Update

Corrected the selection-toolbar integration to use the existing Activity-level `onActionModeStarted()` path already supported by this project. Removed the unsupported `WebView.customSelectionActionModeCallback` reference that does not exist on the project compile target.

The existing Android selection toolbar remains intact, while the app continues to add **Define** and **Highlight** after WebView menu preparation. Android default actions such as Copy, Translate, Search, Share, and Select all are not cleared or replaced.

## Files updated

- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — removed the unsupported WebView API assignment and retained the existing delayed ActionMode menu injection.
- `docs/PHASE_4_SELECTION_ACTIONMODE_FIX.md` — updated this fix documentation.

## Compatibility

- App version remains v37 / 1.36.
- No architecture or database changes.
- No files were removed.
