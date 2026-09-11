# V37 — Selection Menu, TTS Overlay & Highlights Layout Update

Patch that fixes the floating text-selection toolbar, the Read Aloud experience,
and a layout bug in the Highlights list. The original app architecture and
foundation are unchanged; only the reader screen's selection handling, TTS
chrome, word-highlight rendering, and highlight-list row layout are touched.

## What changed

### 1. Define + Highlight in the native selection toolbar
- The Define and Highlight actions now appear directly in Android's floating
  text-selection toolbar (the one that already shows Copy / Translate / Select
  all / Share / Web search). No extra bottom sheet is shown for these.
- Implemented via `WebView.setCustomSelectionActionModeCallback`, which adds the
  items in `onPrepareActionMode` — the last point before the toolbar renders —
  so they survive the WebView's menu rebuild (the previous `onActionModeStarted`
  hook was too early and the items were dropped). The menu is never cleared, so
  every default action stays alongside ours.
- Both actions capture the live selection at click time (not when the toolbar
  appeared) and only finish the ActionMode after the selection text is read
  back, so finishing early can't wipe the selection before it's captured.

### 2. Dismiss the selection toolbar on navigation / touch
- A new `currentSelectionActionMode` reference + `clearReaderSelection()` is
  called before every navigation away from the reader page: TOC / Bookmarks /
  Highlights, Search, Settings, chapter change (`loadChapter`),
  `navigateToUrl`, `goToSpine`, and opening the TTS overlay.
- A fresh tap on the page (ACTION_DOWN while a selection ActionMode is active)
  also dismisses it; the long-press that starts a new selection is untouched.

### 3. Highlights list vertical-text layout fix
- `HighlightListAdapter` now sets an explicit `MATCH_PARENT` width on the
  highlight text `TextView`. The row's text column is a vertical LinearLayout
  sized by `layout_weight` (width 0); a child left at default `wrap_content` was
  measured against a 0-width constraint and wrapped every character onto its own
  line (text rendered vertically). Forcing match_parent makes it wrap normally.

### 4. Dedicated Read Aloud overlay
- Replaced the crowded in-page TTS control row with a full-screen overlay
  (`ttsOverlay`). The original reader top/bottom bars disappear while it is open;
  the overlay header shows the book title and the current section.
- Transport contains only: previous sentence, play/pause, next sentence, and
  stop. The repeat mechanism was removed; prev/next sentence were added.
- Speed, pitch, sleep timer, background-playback toggle, and voice picker moved
  behind the overlay's settings (tune) button into `showTtsSettingsSheet`.
- The top-bar speaker button opens the overlay (or starts playback if idle);
  the overlay's close/back controls minimize it (playback continues).

### 5. TTS word highlight no longer reflows the EPUB content
- `highlightSpokenWord` was rewritten to draw non-mutating overlay rectangles
  (absolutely-positioned divs in a fixed `#livre-tts-hl` container) using
  `Range.getClientRects()`, instead of wrapping text in `<span>`s via
  `surroundContents` / `extractContents`. Because the DOM is never mutated, the
  page layout, columns, and pagination stay exactly as the user sees them —
  content no longer rearranges when TTS is on.
- `clearSpokenWordHighlight` simply removes the overlay container.

## Files updated / added

- `app/src/main/java/com/epubreader/app/ReaderActivity.kt`
  - Added `currentSelectionActionMode` field, `SELECTION_DEFINE_ID` constant,
    `selectionActionCallback`, `addSelectionActionItems`, `clearReaderSelection`,
    and `onActionModeFinished`; rewrote `onActionModeStarted`.
  - Wired `clearReaderSelection()` into navigation paths + WebView touch listener.
  - Added `showTtsOverlay` / `hideTtsOverlay`; rewired TTS transport to the new
    overlay buttons; moved speed/pitch sliders + sleep timer into
    `showTtsSettingsSheet`; slimmed `applyTtsSettings`.
  - Rewrote `highlightSpokenWord` (non-mutating overlay rects) and
    `clearSpokenWordHighlight`.
  - Updated `onPause` and back-press to use the new overlay.
- `app/src/main/res/layout/activity_reader.xml`
  - Removed the old `ttsControls` block; added the full-screen `ttsOverlay`
    (header + book title + section + status + transport row).
- `app/src/main/java/com/epubreader/app/ui/HighlightListAdapter.kt`
  - Explicit `MATCH_PARENT` width on the highlight text `TextView`.
- `app/src/main/res/values/strings.xml`
  - Added `tts_overlay_title`, `tts_close`, `tts_section`, `tts_stop_confirm`.
- `docs/V37_SELECTION_TTS_HIGHLIGHTS_UPDATE.md` (this file).

## Compatibility
- App version remains v36 per the user's standing instruction.
- No architecture or foundation changes; existing single sources of truth
  (colors, strings, dimens, styles) are reused. No hardcoded values added.
