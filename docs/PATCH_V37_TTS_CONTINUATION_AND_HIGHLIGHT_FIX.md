# Patch v37 — TTS continuation and sentence-highlight fix

## Updated
- Fixed the natural-pause state machine so a completed silent pause advances to the next sentence instead of re-creating the same pause indefinitely.
- Serialized TTS completion/error transitions onto the main thread to prevent stale callbacks from interrupting sentence progression.
- Improved TTS start-position detection to use the first readable text actually visible on the current page instead of sampling a point 30% down the viewport.
- Cleared the visual TTS highlight when playback stops or pauses.
- Kept the existing v37 architecture, Room per-book settings, offline voice filtering, playback controls, background service, sleep timer, and EPUB-language voice fallback intact.

## Files updated
- `app/src/main/java/com/epubreader/app/epub/ReaderTtsController.kt`
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt`
- `docs/PATCH_V37_TTS_CONTINUATION_AND_HIGHLIGHT_FIX.md`

No project files or folders were removed. App version remains v37.
