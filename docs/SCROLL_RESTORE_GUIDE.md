# Scroll Restoration — How It Works and How to Extend It

Patch 11 restores the exact scroll position when you return from the EPUB reader
(or from an Author/Series detail view) to a book/row list. This guide explains
the architecture and how to add a new view to it.

## The mental model

There are two separate scroll behaviors, kept deliberately apart:

1. **Top-reset on view switch (Patch 10, unchanged).** When you tap a drawer
   item, change sort order, rescan, or switch grid/list mode, the list lands
   cleanly at the top. This is the hard, non-animated reset from Patch 10 and is
   NOT touched by Patch 11.
2. **Restore-on-return (Patch 11, new).** When you open a book (or open an
   Author/Series detail) and then press back, you return to the exact scroll
   position you left in the originating list.

The restore only applies to the **open-book / return** path. It never changes
the view-switch top-reset.

## Eligible views

`isRestoreEligible(view)` returns `true` for:

- `ShelfView.Library`
- `ShelfView.AuthorsList`
- `ShelfView.SeriesList`
- `ShelfView.AuthorDetail(name)`
- `ShelfView.SeriesDetail(name)`

`ShelfView.Reading` is **excluded** — it always top-resets on return (matches
Patch 10 behavior for that view).

## Data captured

`ScrollAnchor` stores, per view:

- `firstVisiblePosition` + `firstVisibleOffset` — the row and its pixel offset
  from the top (the exact same rows that were on screen).
- `clickedBookId` + `clickedBookPosition` + `clickedBookTopOffset` — for book
  lists, the book you tapped, its adapter index, and its screen offset. Used to
  re-locate the book by id if the list re-sorted after reading progress
  changed.

## Restore strategy (in `tryRestoreScroll()`)

1. **Key-match guard.** A restore only fires if the current view's key matches
   the queued restore key. Each view has a stable key:
   - `Library` → `"Library"`
   - `AuthorsList` → `"AuthorsList"`
   - `SeriesList` → `"SeriesList"`
   - `AuthorDetail(name)` → `"author_detail:$name"`
   - `SeriesDetail(name)` → `"series_detail:$name"`
2. **If the clicked book is at the same adapter index** (list didn't reorder):
   restore the exact first-visible position + offset — same rows as before.
3. **If the clicked book moved** (e.g. sort-by-progress changed its position):
   re-locate it by id and align it to its saved top offset, so the tapped title
   is still visible.

## Where capture and restore happen

- **Capture:** `captureScrollState(clickedBookId)` is called in `openBook()`,
  `openDetails()`, and row click handlers (`bindRowAdapter`) — right before
  navigating away. For book lists, the clicked book id is passed so we can
  re-locate it on return.
- **Restore:** `tryRestoreScroll()` runs inside the adapter's `submitList`
  commit callback (after items are laid out), with a fallback in `onResume()`
  for when content is already present.

## The `pendingRestoreKey` / `pendingReadingTopReset` flags

- `pendingRestoreKey` — set before navigating away, consumed on return **only
  if the current view key matches**. Drawer navigation (`selectDrawer()`)
  always clears it and sets `scrollToTopOnNextContent = true`, so a section
  switch always top-resets.
- `pendingReadingTopReset` — set when opening a book from Currently Reading so
  that view always top-resets on return (currently excluded from restore by
  `isRestoreEligible`, so this is a belt-and-suspenders fallback in
  `onResume()`).

## How to add a new view to scroll-restore

1. Add the new `ShelfView` subclass (or reuse an existing one).
2. In `isRestoreEligible()`, add a branch returning `true` for the new view.
3. In `viewKey()`, add a stable, unique string for the new view (e.g.
   `"my_view:$param"`). This key is what makes the restore match correctly.
4. Make sure the adapter that renders the new view is a `ListAdapter` whose
   `submitList` you can attach a commit callback to — that's where
   `tryRestoreScroll()` is called.
5. If the new view is reachable by tapping a row in another list, capture the
   parent list's state in the row click handler (see `bindRowAdapter()` for the
   pattern) and set `pendingRestoreKey` + `scrollToTopOnNextContent = true` for
   the new view's first load.

## Gotchas

- **LayoutManager recreation wipes scroll.** `bindBookAdapter()` now only
  recreates the `LayoutManager` when grid/list mode or column count actually
  changes. Previously it recreated on every Room emission, which destroyed the
  scroll state. Keep this guard.
- **`GridLayoutManager` extends `LinearLayoutManager`.** When checking whether
  the current layout manager is a linear (list) one, check for
  `GridLayoutManager` first — otherwise the list-mode check always sees a
  `LinearLayoutManager` and never recreates when it should.
- **Restore vs. top-reset are mutually exclusive in intent.** Don't set
  `pendingRestoreKey` on a path that also sets `scrollToTopOnNextContent =
  true` for the same view — they fight each other. Detail-list entry sets
  top-reset for the detail view and a restore key for the parent view; that's
  the correct split.
