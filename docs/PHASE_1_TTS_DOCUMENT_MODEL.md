# Phase 1 — TTS EPUB document model

## Corrective patch
The first Phase 1 package exposed missing helper methods in `ReaderTtsController.kt` during the app's real `kspDebugKotlin`/`compileDebugKotlin` build. This corrective patch restores those TTS-local helpers without changing the Phase 1 architecture.

## What changed
Phase 1 uses a TTS-specific structural XHTML model instead of flattening a chapter before segmentation. Read Aloud preserves readable block boundaries (paragraphs, headings, list items, quotes, and other block content) and source text ranges while building speech segments.

The controller now also contains the sentence splitting, heading heuristic, and long-utterance split helpers required by the Phase 1 segment builder.

## Files updated
- `app/src/main/java/com/epubreader/app/epub/ReaderTtsController.kt`
  - Restored `isLikelyHeading`, `splitIntoSentences`, and `findSplitPoint` used by Phase 1 segmentation.
  - Keeps the structural document/segment approach from the original Phase 1 package.
- `app/src/main/java/com/epubreader/app/epub/ReaderTtsDocument.kt`
  - TTS-only document, block, and segment data model.
- `app/src/main/java/com/epubreader/app/epub/ReaderTtsDocumentBuilder.kt`
  - Tolerant XHTML tokenizer/parser for TTS structure and source ranges.

## Validation
- App version remains v37 (`versionCode 37`; existing `versionName` retained).
- No files or folders were removed.
- All checked `.kt`, `.kts`, `.xml`, `.java`, `.gradle`, and `.md` files have a final newline.
- No `TtsSegment` unresolved type references remain; TTS code references `ReaderTtsSegment`.
- The previously reported missing helper references are present in `ReaderTtsController.kt`.
- ZIP integrity was checked before delivery.
- A full Gradle compile was attempted, but this environment cannot obtain the project's Gradle 8.9 distribution because external network access is unavailable. The delivery does not claim a successful Android Gradle build from this environment.
