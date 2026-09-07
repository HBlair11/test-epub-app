# The Livre Magicae — Patch 16 Release Notes

**Version:** 1.17-patch16.1 (versionCode 17)
**Package:** `com.epubreader.app` (display label "The Livre Magicae")
**Permissions:** only the auto-generated `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. No `INTERNET`.
**Build:** Gradle 8.9, AGP 8.7.2, Kotlin 2.0.20, KSP 2.0.20-1.0.25, JDK 17 (Temurin), compileSdk/targetSdk 35, minSdk 24.

Patch 16 fixes four reader/library issues and adds three settings/drawer refinements.
Two of the fixes (font rendering, scan "Show" button) address behaviour reported
against the same commercial EPUB (*The Love Hypothesis*) that drove Patches 13–15.

> **Patch 16.1 (hotfix):** fixed a crash where reopening the reader settings sheet
> after moving the line-spacing (or margin) slider crashed the app. See
> **Hotfix 16.1** at the bottom.

---

## Issue #1 — Embedded fonts ignored even with "Publisher" font selected

**Symptom:** In certain commercial EPUBs, the book's embedded `@font-face` fonts do
not render — the reader falls back to a system face. Calibre (desktop) and the
Readium Android app render the same book's fonts correctly. Selecting the
**Publisher** font in reader user settings makes no difference.

**Root cause:** These EPUBs ship their embedded fonts **obfuscated** per the
OCF/IDPF font-embedding spec, declared in `META-INF/encryption.xml`. There are two
algorithms in the wild:

| Algorithm URI | Origin | Key | Head length |
|---|---|---|---|
| `http://www.idpf.org/2008/embedding` | IDPF | SHA-1 of the OPF unique-identifier (whitespace stripped) | first 1040 bytes |
| `http://ns.adobe.com/pdf/enc#RC` | Adobe | the UUID unique-identifier, hex-decoded to 16 bytes | first 1024 bytes |

Calibre and Readium transparently de-obfuscate these fonts before rendering. The
app's `EpubResourceResolver` was serving the **raw obfuscated bytes** straight to
the WebView, so the `@font-face` declarations pointed at garbled font data and the
WebView silently fell back to a default face. The CSS and `@font-face` rules were
correct; only the font *bytes* were wrong.

**Fix:** `EpubResourceResolver` now:

1. **Lazily parses** `META-INF/encryption.xml` (via `XmlPullParser`) the first time
   an obfuscated entry is requested, building a `Map<entryPath, algorithmUri>`.
   CipherReference URIs are percent-decoded *before* path normalisation so
   `Fonts/My%20Font.otf` matches the decoded entry path the WebView requests.
2. **Resolves the obfuscation key** from the OPF:
   - Reads `META-INF/container.xml` → `rootfile/@full-path` → the OPF.
   - Reads `package/@unique-identifier` → the matching `dc:identifier` text.
3. **De-obfuscates in memory** by XOR-ing the leading bytes with the key repeated
   (IDPF = first 1040 bytes, Adobe = first 1024 bytes). The EPUB file on disk is
   **never modified** — a fresh cleartext `ByteArray` is served to the WebView.
4. Serves **binary** resources (fonts, images) with a `null` encoding instead of
   `"UTF-8"`, so a bogus charset is no longer attached to binary responses.

Both the IDPF and Adobe algorithms are supported; the unique-identifier is read
straight from the OPF, so there is no fragile hard-coding. De-obfuscation is
fail-closed: if anything in the parse fails, the raw bytes are served unchanged
(reverting to the pre-patch behaviour), so non-obfuscated books are unaffected.

**Files:** `epub/EpubResourceResolver.kt` (rewritten resolver; new `ensureObfuscationParsed()`,
`parseEncryption()`, `readUniqueIdentifier()`, `obfuscationKeyFor()`, `deobfuscate()`,
`deobfuscateIfNeeded()`, `isTextMime()`, shared `XmlPullParserFactoryShared`).

---

## Issue #2 — Tapping a link inside an in-book TOC also turns the page

**Symptom:** In the reader, when the book's own HTML/XHTML Table of Contents is
displayed, tapping a chapter link (e.g. "Chapter 5") navigates to the right
chapter **but also** flips one page — backward if the tap landed on the left half of
the screen, forward if on the right half.

**Root cause:** `ReaderActivity.onSingleTapConfirmed()` unconditionally fired the
page-turn/toggle path for every confirmed tap, and the touch listener returned
`false`, so the WebView *also* processed the link natively. The two behaviours ran
together: the WebView jumped to the link target, and the gesture detector ran the
page-turn — which moved one page relative to the *new* location.

**Fix:** Before running the page-turn/toggle logic, the handler now checks
`binding.webView.hitTestResult`. If the tap landed on an anchor
(`SRC_ANCHOR_TYPE`) or an image-inside-an-anchor (`SRC_IMAGE_ANCHOR_TYPE`), the
handler returns `true` early — suppressing the page-turn and letting the WebView
perform the in-page navigation alone. Non-link taps (page body, margins) keep the
existing page-turn/toggle behaviour.

**File:** `ReaderActivity.kt` → `onSingleTapConfirmed()` (new `tappedLinkOnWebView()` helper).

---

## Issue #3 — "Show" button after a scan dumps you on the Library

**Symptom:** After adding books (scan or import), the snackbar "Scan completed, N
new book(s) found → **Show**" switched to the Library view, forcing the user to
scroll to find the newly added book.

**Fix — new "Recently Added" temp screen:** a transient, non-persisted shelf that
shows **only** the books just imported, in whatever view-mode/layout (grid 2/3/4
or list) the app is currently using.

- `EpubImporter.commitImports()` now returns the **list of new book IDs** (it
  previously returned only a count). `importMultiple()` collects IDs from
  `importUriResult()` the same way.
- `showScanResult(newIds: List<Long>)` stores the previous shelf in
  `viewBeforeRecentlyAdded` and enters `ShelfView.RecentlyAdded(newIds)` with a
  clean scroll-to-top reset.
- `ShelfView.RecentlyAdded` is backed by a new `BookDao.observeByIds(ids)` query
  (`SELECT … WHERE id IN (:ids) ORDER BY added_date DESC`), exposed through
  `BookRepository.observeByIds()`.
- The temp screen shows a **back arrow** (not the hamburger) in the toolbar, and
  both the back button and the toolbar back arrow return to the exact shelf the
  user was on before tapping **Show** (`exitRecentlyAdded()`).
- **`RecentlyAdded` is never persisted to `prefs.lastView`** — so a relaunch never
  lands on a stale/empty id set. It is purely in-memory and is discarded on exit.
- The temp screen defaults to **newest-added first** (its own sort key
  `KEY_RECENTLY_ADDED`), but the sort chip still works for users who want to
  re-sort it. (Because `MainActivity` declares `configChanges` for
  orientation/screenSize, the Activity is not recreated on rotation, so
  `viewBeforeRecentlyAdded` survives rotation.)

**Files:** `epub/EpubImporter.kt` (`commitImports` signature), `data/BookDao.kt`
(`observeByIds`), `data/BookRepository.kt` (`observeByIds`), `ui/BookshelfViewModel.kt`
(`ShelfView.RecentlyAdded`, `flowFor`, `setView`, `defaultSortFor`, `key`), `MainActivity.kt`
(`showScanResult`, `importMultiple`, `exitRecentlyAdded`, back press, `applyView`,
`titleFor`), `res/values/strings.xml` (`recently_added_screen_title`).

---

## Issue #4 — Settings sliders fire while scrolling the settings sheet

**Symptom:** While scrolling the reader user-settings sheet upward, an accidental
tap inside a slider's track area would change that slider's value, because the
slider interpreted the vertical scroll-drag as a seek gesture.

**Fix (the four layers you specified):**

- **Layer A — `NestedScrollView`:** the settings sheet root is now an
  `androidx.core.widget.NestedScrollView` instead of a plain `ScrollView`, so it
  participates in nested scrolling with the sliders.
- **Layer B — vertical padding:** each slider row is wrapped in a `LinearLayout`
  with `paddingTop/Bottom = 10dp`, giving a scroll hit-zone around the slider.
- **Layer C — `dragToSeek()` gate:** a `Slider.dragToSeek(scrollView)` extension
  gates seeking to **deliberate horizontal drags** (slope threshold 1.15). On
  mostly-vertical motion it releases the touch to the parent scroll via
  `requestDisallowInterceptTouchEvent(false)`, so the page scrolls smoothly instead
  of seeking.
- Wired on `fontSizeSlider`, `lineHeightSlider`, and `marginSlider`.

**Files:** `res/layout/dialog_reader_settings.xml` (Layer A + B), `ui/ReaderSettingsSheet.kt`
(Layer C, `dragToSeek()` + `roundToInt()` helper).

---

## Addition #1 — Wider hamburger drawer

**Change:** the nav drawer overlay is now **70% of screen width** (was 60%).
`(w * 70 / 100).coerceAtLeast(280)`. The minimum is raised from 240dp to 280dp to
keep long labels readable on narrow phones.

**File:** `MainActivity.kt` → `setupDrawer()`.

---

## Addition #2 — Margin slider: default 20, range 20–72, step 2

**Change:** the side-margin slider now defaults to **20** (was 8) with a range of
**20 → 72** (was 0 → 48); the step remains **2**. Old persisted values outside
`[20, 72]` are clamped on read and on write, so existing users are moved into range
without data loss. The settings sheet clamps the displayed value before assigning it
to the slider.

**Files:** `data/PrefsManager.kt` (`MIN_MARGIN`, `MAX_MARGIN`, `DEFAULT_MARGIN`,
`STEP_MARGIN`, `margin` getter/setter), `res/layout/dialog_reader_settings.xml`
(`valueFrom=20`, `valueTo=72`), `ui/ReaderSettingsSheet.kt` (`setupMargin`).

---

## Addition #3 — Line-spacing slider: max 2.60

**Change:** the line-height slider's upper bound is now **2.60** (was 2.20). The
minimum (1.0) and default (1.6) are unchanged. Old persisted values are clamped to
`[1.0, 2.6]` on read/write. The settings sheet clamps the displayed value.

**Files:** `data/PrefsManager.kt` (`MIN_LINE_HEIGHT`, `MAX_LINE_HEIGHT`,
`DEFAULT_LINE_HEIGHT`, `lineHeight` getter/setter), `res/layout/dialog_reader_settings.xml`
(`valueTo=2.6`), `ui/ReaderSettingsSheet.kt` (`setupLineHeight`).

---

## Build & verification

Built headless with JDK 17 (Temurin 17.0.12) + Android SDK (platform 35,
build-tools 35.0.0) and the project's Gradle 8.9 wrapper:

```
./gradlew assembleDebug --no-daemon --console=plain --no-build-cache --rerun-tasks
```

Result: `BUILD SUCCESSFUL` with `compileDebugKotlin` executing a full recompile
(no cache). Only two pre-existing, unrelated deprecation warnings
(`Window.statusBarColor` / `navigationBarColor` in `ReaderActivity`). The produced
APK verifies: `versionCode 16`, `versionName 1.16-patch16`, no `INTERNET`
permission, zip integrity OK, and the dex contains the new
`ShelfView$RecentlyAdded` class and the rewritten `EpubResourceResolver`.

| Artifact | Path |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/the-livre-magicae.apk` |
| Output (renamed) | `the-livre-magicae.apk` |

The GitHub Actions workflows (`.github/workflows/android-ci.yml` debug build,
`release.yml` release-on-tag) are unchanged and remain the canonical CI path.

---

## Hotfix 16.1 — Reader settings crash after moving the line-spacing / margin slider

**Symptom:** After changing the line-spacing slider (and/or the margin slider) in
the reader settings sheet, the next time the app was opened and the reader
settings were opened again, the app crashed immediately.

**Root cause:** Android's `Material.Slider.setValue(float)` throws an
`IllegalArgumentException` if the value does not land on an exact step tick —
i.e. `(value - valueFrom) % stepSize == 0`. The previous code only clamped the
persisted value to the slider's **range**, never to its **step grid**.

- **Line spacing** (`valueFrom=1.0, stepSize=0.1`) was the real crasher: a value
  like `1.9` is stored as the IEEE-754 float `1.8999999…`, so on reopen
  `(1.9f − 1.0f) % 0.1f ≠ 0` → throws → crash. The change listener had persisted
  the raw fractional float, so the next open always crashed.
- **Margin** (`valueFrom=20, stepSize=2`) was a near-miss: integer floats are
  exact, so it had not yet crashed, but it also never snapped the loaded value to
  the step grid — a stray odd persisted value would have thrown on open too.

**Fix:**

1. **Line-spacing slider is now integer-backed.** `valueFrom=10, valueTo=26,
   stepSize=1` (the real line-height is `value / 10`, so 16 → 1.6, 26 → 2.6).
   Integers are exact in IEEE-754, so `(value − 10) % 1` is always 0 and
   `setValue()` can never throw. UX is unchanged (same 17 ticks, same range).
2. **`setSafeValue()` + `snapToTick()` helpers** now snap every slider's loaded
   value to its exact step grid before assigning, and wrap the assignment in a
   `try/catch` as a final safety net — so no persisted value can ever crash the
   sheet. Applied to the font-size, line-spacing, and margin sliders.
3. **Change listeners now persist the snapped value** (`snapToTick`), so a value
   that does not land on a tick is never written to SharedPreferences in the
   first place.

**Migration:** old persisted line-height values (e.g. `2.2f`, `1.6f`) are
re-derived on first open as `(value × 10).roundToInt()` (e.g. `22`, `16`) and
re-snapped into the new integer grid cleanly. Old margin values below 20 are
clamped up to 20. No user data is lost.

**Files:** `res/layout/dialog_reader_settings.xml` (line-height slider → integer
grid), `ui/ReaderSettingsSheet.kt` (`setupFontSize`, `setupLineHeight`,
`setupMargin`, new `setSafeValue()` / `snapToTick()` helpers).

**Build:** rebuilt and verified — `compileDebugKotlin` recompiled with no
errors; APK is `versionCode 17`, `versionName 1.17-patch16.1`, no `INTERNET`
permission, zip integrity OK.
