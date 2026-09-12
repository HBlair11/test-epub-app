# Selection Toolbar Popup Update Compile Fix

## Update
- Fixed the `PopupWindow.update(...)` call used by the custom selection toolbar drag logic.
- The drag behavior now uses the valid four-argument `PopupWindow.update(x, y, width, height)` overload.
- No selection toolbar styling, native-toolbar suppression, action layout, or unrelated reader behavior was changed.

## Files updated
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt`
- `docs/SELECTION_TOOLBAR_POPUP_UPDATE_COMPILE_FIX.md`
