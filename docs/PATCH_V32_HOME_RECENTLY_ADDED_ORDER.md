# v32 — Home Recently Added Order Fix

## Change
The Home **Recently Added** shelf now selects the six newest books by `addedDate`, then displays those six books in **ascending `addedDate` order**. This keeps the shelf limited to the most recently added books while making the newest book appear at the end of the row.

## Scope
- Only the dedicated Home shelf ordering changed.
- Library sorting, the transient Recently Added screen, reader behavior, metadata, Continue Reading, Top Authors, Top Series, and existing navigation were not changed.
- No Room schema or migration changes.
- Version remains v32.

## Validation
- `BookshelfViewModel.buildHomeContent()` references were checked after the change.
- `recentlyAdded` now receives the already-selected six-book list without a second descending sort.
- All source/resource files retain a final newline.
