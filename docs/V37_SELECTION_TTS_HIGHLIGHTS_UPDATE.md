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
- `TextView` exposes `setCustomSelectionActionModeCallback` for this, but `WebView`
  does not — a WebView builds its selection toolbar from a private Chromium
  callback that clears and repopulates the menu in `onPrepareActionMode`, so
  items added from the Activity-level `onActionModeStarted` hook get wiped.
  The reliable interception point is `startActionMode` itself, so the reading
  WebView is now a `LivreWebView` subclass that overrides `startActionMode`,
  wraps the WebView's own callback, and (re)adds our items in
  `onPrepareActionMode` after the WebView rebuilds its menu. The menu is never
  cleared, so every default action stays alongside ours.
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
- `app/src/main/java/com/epubreader/app/ui/LivreWebView.kt` (new)
  - Custom `WebView` subclass that overrides `startActionMode` to wrap the
    WebView's own selection callback and (re)add the Define/Highlight items in
    `onPrepareActionMode`, after the WebView rebuilds its menu.
- `app/src/main/res/layout/activity_reader.xml`
  - Switched the reading `<WebView>` to `<com.epubreader.app.ui.LivreWebView>`;
    removed the old `ttsControls` block; added the full-screen `ttsOverlay`
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

## Follow-up patch (same v37)

After user testing, the following refinements were made:

### 6. Selection toolbar position fixed (floating, not top-fixed)
- The `LivreWebView` wrapper now extends `ActionMode.Callback2` instead of plain
  `ActionMode.Callback`, and delegates `onGetContentRect()` to the original
  callback. Android uses the content rect to position the floating toolbar near
  the selected text; the previous plain-Callback wrapper dropped it, causing the
  toolbar to fall back to the top of the screen. The toolbar now floats near the
  selection as the user expects.

### 7. Tap suppression — highlight taps and selection dismissal no longer turn
   pages or toggle chrome
- Added `suppressReaderTapUntilMs` (time-based guard) and `consumingSelectionDismissTap`
  (gesture-consumption flag).
- `HighlightBridge.onHighlightTap` calls `suppressReaderTap()` before showing the
  note/delete sheet; the JS click handler also calls `e.preventDefault()`.
- The WebView touch listener now consumes the entire gesture (DOWN → MOVE → UP)
  when a text selection is active, so dismissing a selection never also turns the
  page or toggles the reader chrome.
- `onSingleTapConfirmed` checks `overlayVisible()`, `shouldSuppressReaderTap()`,
  and `chromeVisible` before deciding to turn the page. When chrome is visible, a
  tap only hides it — never turns the page.

### 8. TTS overlay is a compact bottom panel (EPUB content stays visible)
- The full-screen `ttsOverlay` was replaced with a compact `wrap_content` panel
  anchored to the bottom of the reader (`layout_gravity="bottom"`). The EPUB
  content, top bar, and page indicator stay visible — only the bottom bar is
  hidden to avoid overlap.
- `showTtsOverlay` / `hideTtsOverlay` no longer force-hide the reader chrome.

### 9. Highlight list row uses XML layout
- `HighlightListAdapter` now inflates `item_highlight.xml` (text column with
  `width=0dp + weight=1`, giving the TextView a real width to wrap against).
  This reliably fixes the vertical-text bug that the previous programmatic
  `MATCH_PARENT` approach could not.

### 10. Highlight note sheet button spacing
- The Delete and OK buttons in `showHighlightNoteSheet` now have
  `marginStart` between them (using `app_section_spacing`).

### 11. Overlay state restoration on resume
- `onResume()` now checks whether a modal overlay (TOC / Bookmarks / Search) or
  the TTS panel is visible and forces the reader chrome hidden so the top/bottom
  bars don't reappear alongside the overlay after the phone is closed and reopened.

### 12. Reading Stats / About-Privacy / Vocabulary screens styled consistently
- `activity_reading_stats.xml`, `activity_about_privacy.xml`, and
  `activity_vocabulary.xml` now use `fitsSystemWindows="true"` on the root,
  `?android:colorBackground` toolbar background, and
  `navigationIconTint="?android:textColorPrimary"` — matching the app's standard
  activity convention (system bar / nav bar insets respected).

### Files updated / added in the follow-up

- `app/src/main/java/com/epubreader/app/ui/LivreWebView.kt` — Callback2 + onGetContentRect delegation.
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — tap suppression, TTS overlay rework,
  highlight note button spacing, onResume overlay-state fix, onSingleTapConfirmed guards,
  highlight JS click preventDefault.
- `app/src/main/java/com/epubreader/app/ui/HighlightListAdapter.kt` — rewritten to inflate XML.
- `app/src/main/res/layout/item_highlight.xml` (new) — highlight list row layout.
- `app/src/main/res/layout/activity_reader.xml` — TTS overlay changed to compact bottom panel.
- `app/src/main/res/layout/activity_reading_stats.xml` — fitsSystemWindows + toolbar styling.
- `app/src/main/res/layout/activity_about_privacy.xml` — fitsSystemWindows + toolbar styling.
- `app/src/main/res/layout/activity_vocabulary.xml` — fitsSystemWindows + toolbar styling.
- `docs/V37_SELECTION_TTS_HIGHLIGHTS_UPDATE.md` — this follow-up section.
