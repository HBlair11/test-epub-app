# v32 — Home Recently Added & Actual Chapter Count Fix

## Changes

- Home → Recently Added now selects the six newest books by `addedDate` and displays those six newest-first. This is intentionally Home-only; the Library and its user-controlled sort remain unchanged.
- Home book cards keep the existing 240dp overall height. Cover height is slightly reduced to reserve a fixed two-line title/author text area, with ellipsis after two lines and a small breathing gap between cover and metadata.
- Replaced raw EPUB spine-count presentation for the Home Continue Reading hero with an actual content-chapter detector. It uses the embedded TOC and first content headings to distinguish likely chapters from front matter/back matter such as contents, copyright, dedication, acknowledgements, prologue/epilogue, appendices, notes, bibliography and index.
- Added Room 9 → 10 migration for `chapter_count` and `chapter_index`. Existing books are backfilled when opened; new imports calculate the content chapter count immediately.
- Example behavior becomes `Chapter 7 of 35` rather than `Chapter 14 of 49` when the EPUB has 35 detected content chapters and additional spine entries for front/back matter.

## Preserved

- v32 dedicated Home architecture
- Continue Reading hero and its existing visual treatment
- Favorites, Top Authors, Top Series and shelf adapters
- Existing Library sorting and temporary Recently Added screen behavior
- EPUB reader/WebView/Caesura architecture
- Selection foundation
- VersionCode 32 and no-INTERNET policy
