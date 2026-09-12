# Selection Toolbar Main Actions Update

Updated the custom text-selection toolbar so the primary actions are **Copy, Define, Highlight, More**.

## More menu
- Web Search
- Translate
- Share
- Select all

## Files updated
- `app/src/main/res/layout/reader_selection_toolbar.xml` — reordered and reduced the primary toolbar actions.
- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — moved Web Search and Translate into the More menu while reusing the existing action handlers.
- `docs/SELECTION_TOOLBAR_MAIN_ACTIONS_UPDATE.md` — this update summary.

The app version remains v37 / 1.36, and the existing selection architecture and resource single sources of truth are preserved.
