# v36 — Home accuracy, Dictionary, Read Aloud, and Reading Stats

This release is based on the last approved v32 Home Hero & Shelf Polish project state and keeps the existing reader theme/palette and CI workflow intact.

## Home
- Home Recently Added reuses the same `applySort(... RECENTLY_ADDED, false)` logic used by the main bookshelf and shows the newest six first.
- Home Continue Reading consumes the first item from the same Room `observeCurrentlyReading()` query used by the Currently Reading shelf.
- Opening a book records `last_opened_date` immediately before navigation, and ReaderActivity confirms it on entry.
- Continue Reading displays the EPUB embedded navigation TOC location when available.
- Home cards remain 240dp high, one-line title/author with ellipsis, and non-cropping `fitCenter` covers.

## Selection foundation
The existing narrow selection locator/bridge is preserved for future Highlights/Notes/Search/Define work. It exposes selection data only; it has no filesystem, Room, delete, import, or settings mutation methods.

## Dictionary
- Native WebView selection menu includes Define.
- Exact and case-insensitive local lookup with simple base-form fallback.
- Lookup and bundled database initialization occur off the main thread.
- No online fallback or permission is added.

## Read Aloud
- Uses the installed system TextToSpeech engine/voice only.
- Foreground-only play/pause/stop controls.
- Sentence/paragraph-sized chunks below the TTS input limit.
- Audio focus requested while speaking and released when stopped/paused.
- Chapter completion offers automatic continuation into the next spine item.
- TTS is shut down with the reader lifecycle.

## Reading Stats
- Local Room reading sessions record active reading time and approximate pages/chapters advanced.
- Idle gaps over five minutes are excluded.
- Stats show time this week/year, books finished, current streak, and approximate pages/chapters.
- Privacy copy states that statistics remain on-device.

## Schema
Room is advanced from version 12 to 13 with a real migration creating `reading_sessions`; no destructive migration is used.
