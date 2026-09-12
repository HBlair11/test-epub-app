# Selection Toolbar — Native Hide, Spacing, and Drag Update

## Changes
- Keeps Android/Chromium's native floating selection action menu hidden while the custom reader selection toolbar is active.
- Keeps the custom main actions as Copy, Define, Highlight, and More.
- Keeps More as Web Search, Translate, Share, and Select all.
- Adds more breathing room to the custom toolbar through reusable dimension resources.
- Adds long-press drag repositioning to the custom toolbar actions so the floating toolbar can be moved around the screen without changing the selected text.

## Files updated
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — native selection menu suppression and toolbar dragging.
- `app/src/main/res/values/dimens.xml` — reusable toolbar spacing and size adjustments.

## Files added
- `docs/SELECTION_TOOLBAR_NATIVE_HIDE_AND_DRAG_UPDATE.md` — update summary.

## Validation intent
- Preserve the existing reader architecture and selection functionality.
- Keep app version at v37 / 1.36.
- Use existing color/string/dimension resource patterns rather than hardcoded UI resources.

## Drag behavior
- Dragging is initiated with a long press on one of the main toolbar actions, then moving the finger repositions the floating toolbar. A normal tap still activates the action.
