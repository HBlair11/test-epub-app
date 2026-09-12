# Phase 3 — Exact TTS Highlight Anchoring

## Changes
- Replaced proportional/raw chapter text matching for Read Aloud highlighting with exact structural block indexing and normalized offsets inside the owning XHTML block.
- Sentence and word overlays now resolve directly to the corresponding DOM text nodes without wrapping or mutating EPUB content.
- Kept the existing overlay highlight UI and page-turn behavior.
- Improved pause/resume so a pause reported inside a word resumes from the beginning of that word, avoiding clipped word starts.

## Files updated
- `app/src/main/java/com/epubreader/app/epub/ReaderTtsDocument.kt` — stores normalized block offsets on spoken segments.
- `app/src/main/java/com/epubreader/app/epub/ReaderTtsController.kt` — emits exact block offsets and rewinds pause resume to the current word start.
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — maps sentence/word highlights to the exact structural DOM block and text nodes.
