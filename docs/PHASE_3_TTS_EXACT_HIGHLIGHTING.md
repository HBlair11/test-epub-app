# Phase 3 TTS Highlighting Fix — v37

## Update
- Fixed Read Aloud sentence/word highlighting by anchoring the WebView overlay to the same structural block that produced the spoken TTS segment, with a normalized spoken-segment fallback when WebView leaf-block ordering differs.
- Preserved the existing non-mutating overlay approach; EPUB content is not wrapped or rewritten.
- Changed pause/resume behavior so the saved resume position always rewinds to the beginning of the previous word. If Android pauses mid-word, the previous complete word is replayed and the word highlight resumes on that previous word.

## Files updated
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — strengthened structural DOM anchoring for sentence/word overlays.
- `app/src/main/java/com/epubreader/app/epub/ReaderTtsController.kt` — previous-word pause/resume rewind.
- `docs/PHASE_3_TTS_EXACT_HIGHLIGHTING.md` — updated this Phase 3 record.

## Compatibility
- App version remains v37 (`versionCode 37`).
- No general reader architecture or EPUB foundation was changed.
