# V37 Dictionary and Vocabulary Update

This V37 update replaces the dictionary with a real offline dataset, rebuilds the Define experience as a contextual card, and adds a Vocabulary screen with lookup history and export.

## What changed

### Real offline dictionary (WordNet)

The previous bundled `assets/dict/en.db` contained only ~177 project-authored words, which is why most lookups returned "No local definition found." It has been replaced with a WordNet 3.1-derived database (21 MB, ~146,000 distinct words, ~196,000 definition rows, ~5,900 irregular-lemma mappings, idioms included). The WordNet license text is preserved in `assets/dict/WORDNET_LICENSE.txt` and the About screen now credits WordNet. Lookups remain 100% offline; no network access is used.

### Lookup pipeline (`epub/DictionaryLookup.kt`)

Rewritten with a normalization + fallback chain:

- Strips surrounding punctuation/whitespace and typographic quotes, collapses spaces.
- Multi-word selections are tried as whole phrases first (idioms like "kick the bucket" resolve), with a lemmatized-phrase retry ("kicks the bucket" → "kick the bucket"); a phrase miss falls back to the longest meaningful word.
- Single words walk an irregular-lemma chain (mice → mouse, went → go) then rule-based stems (tenses, plurals, comparatives).
- On a total miss, a "did you mean" list is built from same-prefix candidates filtered by Levenshtein distance.
- Language follows the EPUB's `dc:language` metadata; a `dict/<lang>.db` asset with the same schema can be dropped in later for other languages (falls back to English). The installed copy auto-refreshes when the bundled asset's `user_version` is newer.

### Selection toolbar and definition card

- The custom bottom sheet that popped up when text selection began is removed. **Define** and **Highlight** now live directly in the floating selection toolbar and capture the selection at click time (this also fixes the old sheet capturing only the first word, because `onActionModeStarted` fires when the selection begins, not when the user finishes adjusting it).
- Definitions show in a contextual card (`layout/view_definition_card.xml`) anchored near the selection: above it when there's room, otherwise below, clamped to the screen edges. Entries render with bold part-of-speech bullets; suggestions are tappable to re-look up; a "Look up in other apps" button hands the word to external dictionary apps via `Intent.ACTION_PROCESS_TEXT`.
- Every successful lookup is saved once to the new `dictionary_history` Room table.

### Vocabulary screen

New `ui/VocabularyActivity` (drawer entry after Reading Stats) lists looked-up words with their saved definition, supports per-word delete and clear-all, and exports the list as CSV (Anki-importable) or Markdown via the app's existing FileProvider share flow. Exports are written to the cache directory only.

## Files updated/added

- `app/src/main/assets/dict/en.db` (replaced, WordNet dataset), `WORDNET_LICENSE.txt` (new), `README.txt` (updated attribution)
- `app/src/main/java/com/epubreader/app/epub/DictionaryLookup.kt` (rewritten)
- `app/src/main/java/com/epubreader/app/epub/ReaderSelectionLocator.kt`, `ReaderSelectionBridge.kt` (selection rect support)
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` (selection toolbar, definition card, history save)
- `app/src/main/java/com/epubreader/app/data/DictionaryHistoryEntity.kt`, `DictionaryHistoryDao.kt`, `AppDatabase.kt` (v13 → v14 migration)
- `app/src/main/java/com/epubreader/app/ui/VocabularyActivity.kt`, `VocabularyAdapter.kt`, `res/layout/activity_vocabulary.xml` (new)
- `res/drawable/ic_vocabulary.xml` (new), `res/layout/view_definition_card.xml` (new)
- `res/values/strings.xml`, `res/values/dimens.xml` (definition card dimens), `AndroidManifest.xml`, `MainActivity.kt` (drawer entry)

## Database migration

Room v13 → v14 adds `dictionary_history` (and `tts_settings`, see the Read Aloud notes) with a real `MIGRATION_13_14` — existing records in all other tables are untouched and install-over updates are safe.
