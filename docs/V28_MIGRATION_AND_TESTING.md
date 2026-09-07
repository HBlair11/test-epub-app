# v28 Migration and Testing Guide

## Room migration 5 → 6

v28 adds:

```text
books.source_uri TEXT NULL
UNIQUE INDEX index_books_source_uri(source_uri)
```

The migration is non-destructive. Existing rows retain all data. Their `source_uri`
starts as NULL because v27 did not store the original SAF document URI.

The first successful scan/metadata refresh establishes the current URI.

### Important

Install v28 **over v27**. Do not uninstall the old app before the first v28 launch if
you need the existing Room database.

## Test commands

Fast compile:

```bash
./gradlew compileDebugKotlin --console=plain --no-daemon
```

Resources:

```bash
./gradlew mergeDebugResources --console=plain --no-daemon
```

JVM tests:

```bash
./gradlew testDebugUnitTest --console=plain --no-daemon
```

Debug APK:

```bash
./gradlew assembleDebug --console=plain --no-daemon
```

Full local validation:

```bash
./scripts/validate.sh
```

## What the JVM tests cover

- EPUB 3 `belongs-to-collection` / `collection-type` / `group-position`.
- Calibre metadata priority.
- Legacy metadata fallback.
- Safe import identity matching.
- Ambiguous filename/identifier protection.
- Reader progress calculations.
- Synthetic page calculations.
- Existing page mapping behavior.
- Rescan size/mtime behavior.

The parser tests create tiny EPUB ZIP files themselves, so there is no dependency on
a developer-specific sample path.

## Optional device/instrumentation validation

For database migration and WebView behavior, perform an actual device/emulator test
before a public release. The highest-value cases are:

1. Install v27 with an existing library.
2. Install v28 over it.
3. Confirm library, favorites, bookmarks, progress and currently-reading state remain.
4. Scan the same folder and confirm rows receive stable source identity.
5. Replace one EPUB at the same source document URI.
6. Scan again and confirm the same book row is updated rather than duplicated.
7. Open the replaced book and confirm its previous library state remains.
8. Change reader font/size and confirm reading position remains sensible.
9. Seek using both chapter-level and final per-page timelines.
10. Rotate/background/resume the reader and confirm position persists.

## GitHub Actions testing strategy

`android-ci.yml` runs JVM tests automatically with:

```bash
./gradlew testDebugUnitTest
```

This is intentionally separate from emulator/device tests. JVM tests are fast and
reliable in hosted CI. Instrumentation tests can be added later if a hosted emulator
is worth the extra runtime.
