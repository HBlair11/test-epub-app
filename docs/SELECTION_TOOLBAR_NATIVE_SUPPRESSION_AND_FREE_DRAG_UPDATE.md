# Selection Toolbar Native Suppression and Free Drag Update

This v37 update refines the custom text-selection toolbar based on the latest device-test screenshots.

## Changes
- Keeps Android/Chromium's native selection action toolbar suppressed for the full lifetime of the active selection ActionMode while preserving the native selection handles.
- Changes the custom toolbar to **Copy, Define, Highlight, and ⋮**; the vertical ellipsis opens the existing More menu containing Web Search, Translate, Share, and Select all.
- Removes the separate drag-handle control and duplicate three-dot appearance.
- Makes the entire custom toolbar long-press draggable. A normal tap still activates the tapped action.
- Dragging is free in both horizontal and vertical directions and is clamped to the reader's existing popup edge margin.
- Retains the requested spacing/padding and reuses resource values from `dimens.xml`, `strings.xml`, and `styles.xml`.

## Files updated
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — native toolbar suppression and whole-toolbar long-press/free-drag behavior.
- `app/src/main/res/layout/reader_selection_toolbar.xml` — removes the separate drag handle and uses the ellipsis as the More action.
- `app/src/main/res/values/dimens.xml` — names the More-action dimensions; no reusable sizing is hardcoded in the layout.
- `app/src/main/res/values/styles.xml` — updates the More-action style to use the resource dimensions.
- `docs/SELECTION_TOOLBAR_NATIVE_SUPPRESSION_AND_FREE_DRAG_UPDATE.md` — this update record.
