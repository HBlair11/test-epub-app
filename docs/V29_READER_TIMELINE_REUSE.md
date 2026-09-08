# V29 Reader Timeline Reuse

This v29 update keeps the existing reader pagination engine as the authority for rendered pages.

## What is reused

The visible reader WebView already reports:

- `Caesura.pageCount()` — rendered page count for the current spine item.
- `Caesura.currentPage()` — exact rendered page currently visible.
- `Caesura.gotoPage()` — exact rendered page navigation.
- `Caesura.ratio()` — current position within the rendered chapter.

`ReaderActivity` now feeds the current chapter's reported `pageCount()` into `chapterPageCounts` as soon as it receives the normal progress callback. The existing background measurement continues to populate the rest of the book.

## Whole-book timeline

Once all spine-item counts are known, `ReaderPageMapping` converts:

- global page -> spine item + exact page in that item
- spine item + exact page -> global page

The whole-book seeker therefore uses the same rendered-page counts that the reader already uses for the section header and page indicator.

## Seeker behavior

The platform `SeekBar` continues to own the gesture. The small touch hook only corrects the final ACTION_UP position from the actual touch coordinate. This avoids intercepting the drag stream and avoids repeated WebView navigation while dragging.

On release:

1. the global rendered-page target is resolved with `ReaderPageMapping`;
2. same-chapter seeks call `Caesura.gotoPage(exactPage)` directly;
3. cross-chapter seeks carry the exact page index through the chapter load and call `Caesura.gotoPage(exactPage)` after the target chapter is ready.

No ratio-based conversion is used for an in-chapter target page.

## History

The existing v29 session history remains above the seeker and records explicit navigation events only. Normal page turns, layout restoration, and settings recalculation do not create history entries.
