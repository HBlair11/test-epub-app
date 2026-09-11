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

## Second follow-up patch (same v37)

### 13. Play Protect fix — removed LivreWebView startActionMode override
- Google Play Protect flagged the app because `LivreWebView` overrode
  `startActionMode` and wrapped the system `ActionMode.Callback2` (intercepting
  system selection callbacks looks like a security bypass to the scanner).
- The `startActionMode` override and `selectionMenuDecorator` wiring were removed.
  `LivreWebView` is now a plain pass-through WebView subclass (kept so the layout
  XML doesn't change). Selection items (Define / Highlight) are now added in
  `onActionModeStarted` via `Handler.post` + `postDelayed`, which runs after the
  WebView's own `onPrepareActionMode` menu rebuild.

### 14. TTS sentence segmentation with natural pauses
- `ReaderTtsController.chunkText` was replaced with `buildSegments`, which:
  - Splits text into paragraphs (preserving heading/POV boundaries)
  - Further splits into sentences/clauses at `. ! ? , ; :`
  - Adds pauses via `playSilentUtterance`: 150ms after commas/semicolons, 350ms
    after sentence endings, 400ms after paragraphs, 600ms after headings/POV.
- Headings are detected heuristically (short lines, < 80 chars, no sentence
  punctuation, all-caps or title-case).

### 15. TTS starts from the current page
- `speakChapter` now accepts an optional `startOffset` parameter.
- `startTtsForCurrentChapter` injects JavaScript that uses
  `document.caretRangeFromPoint` at the upper-middle of the viewport to find the
  visible text position, walks text nodes to compute a cumulative character
  offset, and passes it to `speakChapter`.
- `findSegmentIndex` locates the segment containing that offset so playback
  begins from the user's current reading position.

### 16. Voice change takes effect immediately
- The `voiceName` setter now calls `applyVoice()` immediately, so the new voice
  is applied to the engine as soon as it's selected.
- The voice picker pauses TTS if it's playing when a new voice is selected, and
  shows a snackbar: "Voice updated. Press play to continue." The user presses
  play to resume from the same segment with the new voice.

### 17. Sentence highlighting restored and made robust
- Added `onSentenceHighlight` callback to `ReaderTtsController` that fires at
  the start of each segment. It serves as a fallback sentence highlight for TTS
  engines that don't support word-level `onRangeStart` callbacks.
- The `highlightSpokenWord` JS now normalizes whitespace in each text node
  individually during collection, so the concatenated DOM text matches the
  TTS-extracted text. Added case-insensitive fallback search.

### 18. Highlight icon button in Reader menu
- Added an `ImageButton` (`btnHighlights`) in the reader top bar after the
  Bookmarks button, using the existing `ic_highlight` drawable and
  `EpubReaderIconButton` style.
- `showTocBookmarks` now accepts a `selectHighlights` parameter; the button
  opens the TOC/Bookmarks overlay directly on the Highlights tab.

### Files updated in the second follow-up

- `app/src/main/java/com/epubreader/app/ui/LivreWebView.kt` — stripped to plain pass-through.
- `app/src/main/java/com/epubreader/app/epub/ReaderTtsController.kt` — rewritten segmentation,
  startOffset support, voice setter, sentence highlight callback, pause utterances.
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — onActionModeStarted Handler.post,
  startTtsForCurrentChapter JS offset, voice picker pause, sentence highlight callback,
  highlightSpokenWord JS robustness, btnHighlights wiring, showTocBookmarks selectHighlights.
- `app/src/main/res/layout/activity_reader.xml` — added btnHighlights ImageButton.
- `app/src/main/res/values/strings.xml` — added `action_highlights`, `tts_voice_changed_pause`.
- `docs/V37_SELECTION_TTS_HIGHLIGHTS_UPDATE.md` — this section.
