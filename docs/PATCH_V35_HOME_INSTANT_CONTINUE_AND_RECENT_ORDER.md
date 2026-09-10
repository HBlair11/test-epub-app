# Patch v35 — Home Continue Reading + Recently Added correction

This corrective patch is based on the cumulative v33 source, not the broken v34
working tree.

## Continue Reading

Opening a book from Home, Library, or Currently Reading immediately updates the
Home projection. An optimistic state prevents a fast Room emission containing
the previous `last_opened_date` from replacing the newly opened book. Room then
persists the same open event.

## Recently Added

Home's Recently Added shelf always uses `addedDate` descending (newest first),
with database ID as a deterministic tie-breaker. It is independent of the
Library sort selector.

## Reader stability

The existing reader theme registry and palette are preserved unchanged. The
missing `colorToHex` and `themeColor` helpers are restored without changing
reader theme colors.

No chapter-count detector or chapter-count UI is reintroduced.
