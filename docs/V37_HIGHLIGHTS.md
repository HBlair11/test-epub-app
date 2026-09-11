# V37 Highlights Fixes

Three fixes make highlights work the way a standard EPUB reader does, without changing the storage model (path + offset + text + prefix/suffix anchors are kept as-is).

## Full-selection capture

Highlights previously captured only the first word of a selection. Root cause: the reader captured the selection (and showed an action sheet) from `onActionModeStarted`, which fires when the selection *begins* — typically a single word. Capture now happens when the **Highlight** toolbar button is tapped, so the full, final selection is used.

## Visible rendering in dark/sepia themes

Highlights were invisible in dark and sepia themes. Root cause: the dark-mode ink override injected `body * { background-color: transparent !important; }`, which also erased the `<mark>` inline backgrounds. The selector now excludes highlight marks and the TTS word highlight:

```css
body *:not(mark.livre-highlight):not(.livre-tts-word) { background-color: transparent !important; }
```

## Delete from the Highlights tab

Each row in the reader's Highlights tab now has a trash button (in addition to the existing delete inside the note sheet). Deleting removes the Room record and unwraps the `<mark>` from the current page if it is visible.

## Better re-anchoring on chapter reload

`injectHighlightIntoWebView` now anchors with the most specific locator available, in order:

1. Resolve the stored start element path and search for the text within that element only — a repeated phrase no longer matches an earlier occurrence elsewhere in the chapter.
2. Prefix-anchored search across the whole chapter text.
3. Bare text search as the last resort.

## Files updated

- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` (capture timing, CSS fix, delete wiring, injection strategy)
- `app/src/main/java/com/epubreader/app/ui/HighlightListAdapter.kt` (optional per-row delete button)
- `res/values/strings.xml` (`highlight_deleted`, `selection_none` replaces the single-word-only message)
