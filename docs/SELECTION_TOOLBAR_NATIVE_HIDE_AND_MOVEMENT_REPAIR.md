# Selection toolbar native-hide and movement repair

This patch corrects the selection-toolbar behavior requested after testing the custom selection toolbar.

## Changes
- Repeatedly hides Android/Chromium's native selection ActionMode floating menu while the custom reader toolbar is active. This avoids the native toolbar reappearing after selection/layout changes on WebView/Android combinations.
- Keeps the native ActionMode available for the actual text selection and selection handles; only its action surface is suppressed.
- Makes the primary custom toolbar more breathable with larger minimum action widths, horizontal action margins, existing padding, and the 56dp toolbar height.
- Adds a dedicated drag handle at the end of the custom toolbar. Dragging the handle moves the floating toolbar without interfering with Copy, Define, Highlight, or More taps.
- More still contains Web Search, Translate, Share, and Select all.

## Files updated
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt`
- `app/src/main/res/layout/reader_selection_toolbar.xml`
- `app/src/main/res/values/dimens.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/styles.xml`

## Validation
- App version remains v37 / versionName 1.36.
- Source files retain a final newline.
- XML files were checked for well-formedness.
- The project file inventory was compared against the original v37 baseline; no baseline files were removed.
- Full Gradle compilation was not possible in this environment because the Gradle 8.9 distribution could not be downloaded due network/DNS restrictions.
