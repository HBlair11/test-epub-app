# Phase 1 — TTS EPUB document model

## What changed
Phase 1 replaces the previous TTS chapter-flattening path with a TTS-specific
structural XHTML model. Read Aloud now preserves readable block boundaries
(paragraphs, headings, list items, quotes, and other block content) and source
text ranges while building speech segments.

This is intentionally scoped to TTS and does not change the app's general EPUB
architecture, Room schema, reader navigation, WebView architecture, or app
version.

## Files updated
- `app/src/main/java/com/epubreader/app/epub/ReaderTtsController.kt`
  - Builds TTS segments from the structural document model.
  - Resolves the existing WebView text offset against segment source ranges.
  - Keeps existing TTS UI, service, settings, and chapter-continuation hooks.
- `app/src/main/java/com/epubreader/app/epub/ReaderTtsDocument.kt`
  - New TTS-only document, block, and segment data model.
- `app/src/main/java/com/epubreader/app/epub/ReaderTtsDocumentBuilder.kt`
  - New tolerant XHTML tokenizer/parser for TTS structure and source ranges.

## Validation
- App version remains v37 (`versionCode 37`, existing `versionName` retained).
- No files or folders were removed.
- All checked source files have a final newline.
- The project was checked for obvious unresolved TTS model references and built
  with the Gradle wrapper before packaging.
