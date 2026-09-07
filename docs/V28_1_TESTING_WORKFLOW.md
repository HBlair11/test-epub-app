# v28.1 Testing Workflow

## Normal APK CI

`.github/workflows/android-ci.yml` intentionally does **not** run
`testDebugUnitTest`.

The normal APK workflow is responsible for producing and validating the installable
APK. A failing or incomplete test suite therefore cannot prevent the normal debug APK
from being compiled.

## Manual test workflow

`.github/workflows/android-tests.yml` is a separate manually triggered workflow.

In GitHub:

1. Open the repository.
2. Open **Actions**.
3. Select **Android Tests**.
4. Click **Run workflow**.
5. Leave **Also run Android instrumentation tests on an emulator** unchecked for the
   fast JVM-only test pass, or enable it when database/device tests should also run.
6. Select the branch and click **Run workflow**.

The workflow is intentionally separate from APK compilation.

## What the JVM tests cover

The parser tests create small EPUB ZIP files during the test run. No developer-specific
EPUB path or Android Studio project setup is required.

They exercise:

- EPUB 3 series metadata.
- Calibre metadata priority.
- Legacy metadata fallback.
- Import identity matching.
- Stable SAF source URI matching.
- Ambiguous identifier protection.
- Ambiguous filename protection.
- Reader progress calculations.
- Synthetic page calculations.
- Existing page mapping behavior.
- Rescan fingerprint behavior.

This is the preferred fast regression suite for GitHub Actions.

## Android instrumentation tests

The optional instrumentation job starts a hosted Android API 35 emulator and runs:

```bash
./gradlew connectedDebugAndroidTest --console=plain --no-daemon --stacktrace
```

The current instrumentation suite includes the Room database smoke test. This is useful
for validating Android/Room behavior that cannot be fully exercised by JVM unit tests.

It is intentionally optional because emulator startup is substantially slower than the
JVM suite.

## Sample EPUB testing without Android Studio

You do not need Android Studio to test EPUB parsing.

`EpubParserTest` creates a minimal valid EPUB in the JVM test process using
`ZipOutputStream`. This is preferable to committing a large real-world EPUB merely for
basic parser regression coverage.

When a real-world EPUB exposes a parser bug, add a small representative fixture under
`app/src/test/resources/` only when the case cannot be reproduced with a generated test
EPUB. Keep fixtures small and legally distributable.

For example, a future fixture can be loaded with:

```kotlin
val file = File(requireNotNull(javaClass.getResource("/fixtures/sample.epub")).toURI())
```

Do not commit copyrighted commercial books as test fixtures.

## Recommended testing workflow for future development

### Small Kotlin/logic change

Run:

```bash
./gradlew testDebugUnitTest --console=plain --no-daemon
```

### EPUB parser/import change

Run the same JVM suite. The generated EPUB tests exercise the parser without an
emulator.

### Room schema/migration change

Run the JVM suite first, then the manual **Android Tests** workflow with instrumentation
enabled.

### Reader/WebView/lifecycle change

Run the manual Android Tests workflow with instrumentation enabled, then perform the
manual reader smoke checks documented in `V28_MIGRATION_AND_TESTING.md` on a real
Android device or emulator.

### APK/release change

Use the normal **Android CI** workflow. Do not make APK compilation depend on the test
workflow.

## Why this structure is intentional

The project now has two independent validation paths:

```text
Android CI
    ↓
compile + resources + APK + signing + APK validation

Android Tests (manual)
    ↓
JVM regression tests
    ↓
optional Android emulator tests
```

This gives you reliable APK production while retaining a proper automated regression
suite that can be run from GitHub's Actions UI without Android Studio.
