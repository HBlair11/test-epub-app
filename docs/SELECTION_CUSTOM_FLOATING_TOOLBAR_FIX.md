# Selection Custom Floating Toolbar Fix

## Summary
Replaced the unreliable attempt to add Define/Highlight into WebView Chromium's native floating ActionMode menu with a reader-owned floating selection toolbar. The WebView still owns the real text selection and selection handles; the reader toolbar reuses the existing selection capture, dictionary, and highlight functionality.

## Toolbar actions
- Copy
- Translate (Android `ACTION_PROCESS_TEXT`)
- Define
- Highlight
- Web Search
- More: Share and Select all

## Files updated
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — selection toolbar lifecycle, positioning, and actions.
- `app/src/main/res/layout/reader_selection_toolbar.xml` — toolbar layout.
- `app/src/main/res/drawable/reader_selection_toolbar_bg.xml` — toolbar background.
- `app/src/main/res/values/dimens.xml` — reusable toolbar dimensions.
- `app/src/main/res/values/strings.xml` — toolbar strings.
- `app/src/main/res/values/styles.xml` — shared toolbar action text style.

## Architecture
No reader architecture, EPUB model, Room schema, TTS controller, or persistent highlight model was replaced. Existing selection locator, dictionary, and highlight code remains the source of behavior for those features.
