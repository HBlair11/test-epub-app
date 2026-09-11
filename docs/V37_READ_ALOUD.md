# V37 Read Aloud (TTS) Update

Read Aloud grows from a basic play/pause chapter reader into a full reading-aloud experience, still built on the system TTS engine with no network use.

## Voices and language

- The voice picker (new tune icon in the reader TTS controls) lists **offline voices only** — voices flagged `isNetworkConnectionRequired` are filtered out.
- "Automatic (book language)" uses the EPUB's `dc:language` for engine language selection; an explicit voice overrides it.

## Speed and pitch

- Speed range is now **0.5x–3.0x** in 0.05 steps (previously 0.5x–1.5x); a pitch slider (**0.5x–2.0x**) sits next to it. Defaults: 0.9x speed, 1.0x pitch.
- Slider values are also stored per book in a new `tts_settings` Room table, so each book remembers its own rate/pitch/voice; books without a row use the app-wide defaults.

## Sentence control

- **Skip back/forward** buttons jump between sentences using `QUEUE_FLUSH`.
- **Repeat** cycles through three modes — off → repeating the current sentence → looping a single word — with the word loop cutting the sentence and replaying just that word until turned off (useful for pronunciation practice).
- The status line shows "Sentence X of Y" while playing.

## Visual sentence highlighting (bimodal reading)

The sentence currently being spoken is tinted with a soft background, and using the engine's `onRangeStart` word callbacks the exact word being spoken gets a stronger highlight (`tts_word_highlight` color) directly in the page. When the spoken word moves past the current page, the reader auto-turns the page via the existing Caesura pagination API. All highlights are cleaned up on stop.

## Background playback and notification

- A new foreground service, `tts/ReaderTtsService` (`mediaPlayback` type), keeps read-aloud alive when the screen is off or the app is backgrounded. It is opt-in via the **Keep reading in background** switch in the Read Aloud settings sheet (off by default — the old stop-on-pause behavior is preserved when it's off).
- The persistent notification shows the book title with Pause/Resume and Stop actions (the label follows the playback state, and pausing keeps the notification alive so it can resume) and returns to the reader on tap.
- Manifest additions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` (requested at runtime on Android 13+ only when background playback is enabled).

## Sleep timer

The timer icon offers 5/10/15/30/45/60-minute options plus Off. While running, the status line counts down remaining minutes; when it finishes, playback stops and a small "Good night" confirmation appears.

## Files updated/added

- `app/src/main/java/com/epubreader/app/epub/ReaderTtsController.kt` (rewritten: voices, pitch, skip, repeat, sleep timer, onRangeStart)
- `app/src/main/java/com/epubreader/app/tts/ReaderTtsService.kt` (new)
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` (expanded TTS wiring, settings sheet, voice picker, word-highlight JS, per-book settings)
- `app/src/main/java/com/epubreader/app/data/TtsSettingsEntity.kt`, `TtsSettingsDao.kt`, `AppDatabase.kt` (v13 → v14 migration)
- `app/src/main/java/com/epubreader/app/data/PrefsManager.kt` (`ttsPitchProgress`, `ttsBackgroundPlayback`, widened `ttsSpeedProgress` to 0..50)
- `res/layout/activity_reader.xml` (two-row TTS controls), `res/drawable/ic_skip_back.xml`, `ic_skip_forward.xml`, `ic_repeat.xml`, `ic_timer.xml`, `ic_tune.xml` (new)
- `res/values/colors.xml` (`tts_word_highlight`), `res/values/strings.xml`, `AndroidManifest.xml`

## Database migration

`tts_settings` is created by the same real `MIGRATION_13_14` (no destructive fallback); rows cascade-delete with their book.
