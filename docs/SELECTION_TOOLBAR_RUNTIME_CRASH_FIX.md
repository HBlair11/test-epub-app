# Selection Toolbar Runtime Crash Fix

## Update

Fixed a runtime crash that occurred when selecting text after the previous selection-toolbar build fix.

## Changes

- Removed calls to `ActionMode.hide(0L)` from WebView touch dispatch and the repeating selection-menu suppression runnable.
- Kept the Chromium `ActionMode` alive so native selection handles continue to work.
- Native action items are suppressed by clearing the ActionMode menu instead of hiding the platform mode during selection callbacks.
- Preserved the existing custom toolbar layout, spacing, floating placement, More menu, and free-movement implementation from the preceding patch.

## Files updated

- `app/src/main/java/com/epubreader/app/ReaderActivity.kt`
- `docs/SELECTION_TOOLBAR_RUNTIME_CRASH_FIX.md`
