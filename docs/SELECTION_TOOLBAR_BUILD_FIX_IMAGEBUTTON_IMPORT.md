# Selection Toolbar Build Fix — ImageButton import

## Update
Fixed the selection toolbar build error caused by `ReaderActivity.kt` using `ImageButton` without importing `android.widget.ImageButton`.

## Files updated
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — added the missing `ImageButton` import.
- `docs/SELECTION_TOOLBAR_BUILD_FIX_IMAGEBUTTON_IMPORT.md` — this update note.

No other selection-toolbar behavior, layout, spacing, movement, or application architecture was changed in this patch.
