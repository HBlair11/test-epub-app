# Phase 2 — TTS playback state, page anchoring, and highlighting

## What changed

Phase 2 fixes the runtime TTS problems found while testing Phase 1:

- TTS segmentation now preserves punctuation boundaries for periods, commas, semicolons, and colons instead of collapsing a whole paragraph into one utterance.
- Natural pauses are handled by a dedicated silent-utterance callback path so a pause cannot recursively replay itself or stop normal progression.
- Playback keeps the current character reported by `UtteranceProgressListener.onRangeStart` and resumes from the unspoken suffix after pause.
- Skip controls stop the current utterance before starting the requested segment, preventing stale callbacks from advancing playback unexpectedly.
- The current reader page is used to locate the first visible readable source character instead of probing an arbitrary point and falling back to chapter zero.
- Sentence and word highlighting are anchored to the structural TTS segment/source range instead of searching the entire chapter for a repeated sentence string.
- Offline voice application falls back to the EPUB/default locale if a selected voice cannot be applied.

## Files updated

- `app/src/main/java/com/epubreader/app/ReaderActivity.kt`
- `app/src/main/java/com/epubreader/app/epub/ReaderTtsController.kt`

## Files retained

The Phase 1 TTS document model remains the foundation:

- `app/src/main/java/com/epubreader/app/epub/ReaderTtsDocument.kt`
- `app/src/main/java/com/epubreader/app/epub/ReaderTtsDocumentBuilder.kt`

No unrelated reader architecture was migrated or rewritten. App version remains v37.
