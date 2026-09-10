# v32 GitHub Actions Compile Fix

This patch fixes the v32 source tree so it compiles cleanly in the project's GitHub Actions workflow.

## Fixes

- Merged the duplicate `ReaderActivity` companion objects. Kotlin permits only one companion object per class; `EXTRA_BOOK_ID`, `PAGE_TURN_DURATION_MS`, and the selection action ID now live together.
- Replaced the unsupported `WebView.customSelectionActionModeCallback` usage with `ReaderActivity.onActionModeStarted(...)`, which preserves the native WebView text-selection toolbar and adds the v32 foundation action without requiring a WebView API that does not exist on the project's compile target.
- Added the missing `BookRepository.observeLastOpened()` flow required by `BookshelfViewModel` for the Continue Reading card.
- Preserved version **v32**; this is a compile-fix patch, not a version upgrade.
- Preserved the existing app architecture and EPUB reader implementation; no new feature behavior was added beyond restoring the v32 selection foundation wiring.

## Validation notes

The source-level errors reported by the GitHub Actions Kotlin compiler were addressed directly. The local environment does not have the Gradle 8.9 distribution available offline, so a full local Gradle compile could not be completed here.
