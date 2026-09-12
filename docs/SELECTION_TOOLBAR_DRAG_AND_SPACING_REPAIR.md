# Selection Toolbar Drag and Spacing Repair

This update is intentionally limited to the existing custom text-selection toolbar.

## Changes
- Kept the working Android native selection-toolbar suppression unchanged.
- Kept the existing main actions: **Copy · Define · Highlight · ⋮**.
- Kept the existing More menu behavior.
- Restored the approved custom-toolbar sizing and spacing values: 56dp height, 8dp toolbar padding, 16dp action horizontal padding, 48dp minimum action width, and 2dp action margins.
- Applied the same action spacing/margins to the More button.
- Reworked the existing long-press drag handling so the toolbar retains its current position and updates through explicit top/start popup coordinates while dragging.
- Dragging remains constrained by the app-wide screen edge margin.
- No reader architecture, TTS behavior, selection actions, or native-menu suppression strategy was otherwise changed.

## Files updated
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt`
- `app/src/main/res/layout/reader_selection_toolbar.xml`
- `app/src/main/res/values/styles.xml`
- `docs/SELECTION_TOOLBAR_DRAG_AND_SPACING_REPAIR.md`
