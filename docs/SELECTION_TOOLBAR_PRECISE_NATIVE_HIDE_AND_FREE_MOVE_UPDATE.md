# Selection Toolbar — Native Hide, Spacing Preservation, and Free Movement

This v37 patch corrects the custom text-selection toolbar behavior without changing the reader architecture.

## Changes

- The Android/Chromium selection ActionMode remains alive for native selection handles, but its menu is cleared repeatedly while the selection is active so the native floating action toolbar does not remain visible over the custom toolbar.
- The custom toolbar keeps the improved 56dp height, 8dp outer padding, 16dp action padding, 48dp action minimum width, and small horizontal action margins from the previous spacing update.
- The primary toolbar is now exactly **Copy · Define · Highlight · vertical three-dot More icon**. There is no separate `More` text action. The existing More popup continues to provide Web Search, Translate, Share, and Select all.
- The More control uses the existing `ic_more_vert` vector drawable instead of a text glyph, avoiding duplicate-looking vertical-dot glyphs.
- A long-press anywhere on the toolbar starts a two-dimensional drag. Normal taps continue to invoke the selected action.
- Dragging uses the same toolbar window coordinate system for its initial position and subsequent deltas, preventing the first-move jump.
- Dragging and initial placement are clamped to the app-wide `app_screen_edge_h` edge padding on all four edges, reusing the existing reader-wide screen gutter rather than introducing a hardcoded value.

## Files updated

- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — native selection-menu suppression and free two-dimensional toolbar dragging.
- `app/src/main/res/layout/reader_selection_toolbar.xml` — replaced the text More action with the existing vertical-dots icon.
- `app/src/main/res/values/styles.xml` — More icon styling and 48dp touch target.
- `app/src/main/res/values/dimens.xml` — retained the previous spacing values and removed obsolete More-text dimensions.
- `docs/SELECTION_TOOLBAR_PRECISE_NATIVE_HIDE_AND_FREE_MOVE_UPDATE.md` — this update record.

Version remains **v37 / 1.36**.
