# Selection Toolbar Tap-Drag Action Area Update

## Update
- Extended the existing custom selection-toolbar drag behavior to the action area.
- A simple tap on Copy, Define, Highlight, or More still performs that action.
- If the same touch moves beyond the normal touch slop, the toolbar moves instead and the action is not triggered.
- Preserved free horizontal and vertical movement, existing edge clamping, native Android toolbar suppression, and all approved toolbar spacing dimensions.

## Updated files
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt`
- `docs/SELECTION_TOOLBAR_TAP_DRAG_ACTION_AREA_UPDATE.md`
