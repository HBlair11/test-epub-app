# The Livre Magicae v28 / 1.27 — Patch Notes

## Reliability and library identity

- Added `source_uri` to `BookEntity` as the stable SAF document identity.
- Added Room migration **5 → 6** for the new source identity and unique index.
- Folder rescans now use the exact source URI as the primary fast-skip identity.
- Legacy rows without `source_uri` are intentionally processed once so their source identity is upgraded instead of being permanently skipped by filename.
- Replacing an EPUB at the same SAF document URI now updates the existing library row rather than creating a second row, preserving reading state, favorites, bookmarks, currently-reading state, last-opened date, and reading position.
- Persistable read permission is requested when importing a single SAF URI when the provider supports it.
- Destructive Room migration fallback was removed. Future schema changes must have explicit migrations.

## Metadata refresh

- Metadata refresh now loads existing books once and builds in-memory lookup maps for:
  - checksum → book
  - source URI → book
  - identifier → books
  - filename → books
- Matching order is:
  1. exact checksum
  2. exact stable source URI
  3. unique identifier
  4. safe filename fallback when there is no identifier conflict
  5. SKIPPED when no safe match exists
- Metadata refresh never replaces cached EPUB content or reading state.
- Stable source identity is written during refresh so legacy books are upgraded even when metadata is unchanged.
- Report UI now shows a scan summary and clearer per-book details.
- Hard-coded metadata status strings were moved to `strings.xml`.

## Reader performance and seeking

- Progress writes to Room are now debounced and persisted immediately on lifecycle boundaries instead of writing on every progress callback.
- Added pure `ReaderProgressMath` calculations and regression tests.
- The per-page measurement pass now prioritizes the current chapter, then nearby chapters, before continuing through the remaining spine items.
- Until all page counts are known, the reader shows `Chapter X of Y` and keeps the seeker at chapter level. Once measurement completes, the accurate page-level timeline becomes active.
- Existing seek callback/token protections remain in place so stale WebView callbacks cannot overwrite a newer seek position.

## UI consistency

- Removed the invalid `toc_item_text` ColorStateList attempt from `values/colors.xml`.
- TOC selected text color is now applied programmatically using the existing palette resources; no `res/color/` directory is required.
- System status and navigation bars are solid black in day and night app themes, including Book Details, Search, Metadata Refresh, Reader Settings, Reader chrome, and dialogs/sheets inheriting those themes.

## Release and CI

- Version bumped to **versionCode 28 / versionName 1.27**.
- Release signing is now production-key based when `keystore.properties` is present.
- `scripts/release.sh` refuses an unsigned production release and verifies the certificate with `apksigner`.
- GitHub release workflow now expects dedicated production signing secrets and verifies the release APK signature.
- GitHub CI now runs `testDebugUnitTest` in addition to Kotlin/resource/build checks.

## Tests added or improved

- Self-contained EPUB parser tests; no developer-specific EPUB path.
- EPUB 3 series metadata tests.
- Calibre/legacy metadata-priority tests.
- Import identity tests for checksum, stable URI, ambiguous identifier, and ambiguous filename cases.
- Reader progress math tests.
- Existing rescan/page-mapping/page-map regression tests retained.

## Important upgrade note

Existing v27 databases migrate automatically from Room schema 5 to 6. Existing rows
cannot be given a true historical SAF URI because v27 did not store it. The first
successful folder scan or metadata refresh upgrades each matching row with its current
SAF document URI.

Do not uninstall the v27 app before installing v28 if you want Room to perform the
migration and retain library state.
