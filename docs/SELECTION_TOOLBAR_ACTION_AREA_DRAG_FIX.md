# Selection Toolbar Action-Area Drag Fix

## Update

The custom selection toolbar now applies its drag gesture interceptor recursively to the entire toolbar, including Copy, Define, Highlight, and More. A stationary tap is forwarded to the existing action click handler, while any touch that moves beyond the normal touch threshold is treated as a toolbar drag and does not trigger the action.

## Files updated

- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — applied the existing toolbar drag gesture to all child/action views without changing their existing click actions.
- `docs/SELECTION_TOOLBAR_ACTION_AREA_DRAG_FIX.md` — documented this focused fix.

## Preserved behavior

- Android's native selection toolbar remains hidden.
- Main actions remain `Copy · Define · Highlight · ⋮`.
- The existing More menu remains unchanged.
- Approved toolbar dimensions remain 56dp height, 8dp toolbar padding, 16dp action horizontal padding, 48dp minimum action width, and 2dp action margins.
- Movement remains free horizontally and vertically and uses the existing app-wide edge margin.
- App version remains v37 / 1.36.
