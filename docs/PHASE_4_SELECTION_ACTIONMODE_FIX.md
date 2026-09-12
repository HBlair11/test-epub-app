# Selection ActionMode Regression Fix

## Summary
Restored the reader's Define and Highlight actions in Android's native text-selection floating toolbar without replacing or intercepting WebView/Chromium's selection ActionMode. Android's existing Copy, Translate, Search, Share, Select all, and other platform actions remain available.

## Updated files
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — registers a public `customSelectionActionModeCallback` on the reader WebView, adds Define/Highlight during ActionMode creation/preparation, and keeps the existing selection capture/actions.
- `app/src/main/java/com/epubreader/app/ui/LivreWebView.kt` — retained the existing custom WebView type and documented that it does not intercept private selection callbacks.
- `docs/PHASE_4_SELECTION_ACTIONMODE_FIX.md` — this change log.

## Preserved
- App version remains v37 / versionName 1.36.
- Existing ReaderSelectionBridge, ReaderSelectionLocator, dictionary lookup, highlight persistence, and Android default selection actions were reused.
- No architecture or Room schema changes.
