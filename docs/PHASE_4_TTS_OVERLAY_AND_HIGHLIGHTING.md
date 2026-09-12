# Phase 4 — TTS-only reader chrome and highlighting/resume behavior

## Updates
- When Read Aloud opens, the normal Reader Settings chrome is hidden: TOC, Bookmarks, Highlights, Search, Settings, book-info header, bottom timeline/history controls, and page indicator are not shown alongside the TTS panel.
- Closing Read Aloud restores the reader chrome to the visibility state it had before TTS opened.
- Removed the `Sentence X of Y` status display from the TTS panel. The status now shows `Reading` while playback is active and `Paused` while paused.
- Reworked the TTS DOM highlight mapping to locate the spoken sentence inside the actual leaf block text and then map sentence/word offsets through the normalized DOM text-node map. The EPUB content itself is not mutated.
- Pause/resume continues to use the TTS controller's previous-word rewind behavior: the resume position is the beginning of the word before the word where Android last reported progress.

## Files updated
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — TTS-only chrome visibility, Reading/Paused status, and DOM highlight mapping.
- `app/src/main/res/values/strings.xml` — added reusable TTS status strings.

## Files added
- `docs/PHASE_4_TTS_OVERLAY_AND_HIGHLIGHTING.md` — this update summary.
