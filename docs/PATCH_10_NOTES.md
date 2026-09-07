# Patch 10 — System Bars, Reader Search Input, Dark Theme Text, View-Switch Scroll Fix

**App:** Caesura
**Version:** 1.10-patch10 (versionCode = 10)
**Base:** Patch 9 (`1.9-patch9`)
**Build command:**
```bash
cd /home/user/workspace/patch10
export JAVA_HOME=/home/user/workspace/tools/jdk-17.0.20.1+1
export ANDROID_HOME=/home/user/workspace/tools/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug --no-daemon --console=plain
# APK -> app/build/outputs/apk/debug/app-debug.apk
```

This patch addresses five areas you reported:
1. The phone status bar and nav buttons are now always solid black across the
   whole app — main app, library, and the EPUB reader itself — regardless of
   the active reading theme or app day/night mode.
2. The reader content stays within the status-bar and nav-button margins
   (just like the main app screen), so the book page never slips under the
   system bars.
3. The reader in-book search text input no longer clashes: dark reader-palette
   background, light text — readable on every reading theme.
4. In dark reading theme, dark inline-styled text (some EPUBs hard-code a dark
   color on individual paragraphs/spans) is now forced to the light ink color,
   so dark-on-dark text no longer disappears.
5. Switching between *Currently Reading* and *Library* (and between any book
   list, grid, or row view) now lands cleanly at the top with no visible
   fast-scroll, mid-screen land, or staggered item pop-in — consistent across
   list mode, grid-of-2, grid-of-3, and grid-of-4.

---

## 1. Always-black system bars

### What changed
- **`app/src/main/res/values/themes.xml`** — every window theme now pins the
  system bars to black and disables the "light icons" flag (black bars need
  white icons). This applies to:
  - `Theme.EpubReader` (main app, search, details, splash — every non-reader
    activity).
  - `Theme.EpubReader.Reader` (the reader activity).
  - `Theme.EpubReader.Reader.Sheet` (the reader settings bottom sheet —
    previously missed, which could let the bars flip while settings were
    open).

  Added to all three:
  - `statusBarColor = @color/black`
  - `navigationBarColor = @color/black`
  - `windowLightStatusBar = false`
  - `windowLightNavigationBar = false`
  - `enforceStatusBarContrast = false` (API 29+; stops the OS from auto-tinting
    the bar for contrast on transparent backgrounds)
  - `enforceNavigationBarContrast = false` (same, for the nav bar)

  The previous `transparent` bar styles and `windowLightStatusBar=true` on the
  main theme were removed.

- **`app/src/main/res/layout/activity_main.xml`** — the top-level
  `DrawerLayout` root now has `android:background="@color/black"`, and the
  content `CoordinatorLayout` has `android:background="?android:colorBackground"`
  (mint, same as before). This means the area *behind* the system-bar gutters
  is black, so even if a bar were ever transparent the gutter is still black;
  the actual app content stays the normal palette color.

- **`app/src/main/java/com/epubreader/app/ReaderActivity.kt`** —
  `applyWindowTheme()` now paints the window decor and the root container
  black (`window.statusBarColor = Color.BLACK`, `window.navigationBarColor =
  Color.BLACK`, `window.decorView.setBackgroundColor(Color.BLACK)`,
  `binding.root.setBackgroundColor(Color.BLACK)`). The WebViews and the static
  chrome bars (top/bottom/overlays) still use the reading-theme page color and
  eggplant chrome color respectively.

### Why both XML theme and programmatic code
The XML theme items set the bars for every activity that declares the theme in
the manifest. The programmatic `window.statusBarColor = Color.BLACK` in the
reader is belt-and-suspenders for `targetSdk = 35` edge-to-edge behavior — some
Android 15 setups can still make the bars effectively transparent even with a
theme color set, and the programmatic call (plus the black root/decor
background) guarantees the bar region is black no matter what.

---

## 2. Reader content stays within the system-bar margins

No new code was needed here — the reader already had a window-inset listener
that pads the root container by the full system-bar inset:
```kotlin
ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
    insets
}
```
This root padding is the **single source of truth** for the reader margins.
Because the root is now black (item 1), the gutter behind the status bar and
nav buttons is black, and the WebView + top bar + bottom bar sit inside the
padded area — exactly like the main app screen. **No additional padding was
added to the top/bottom bars** (that would double-inset and shrink the content).

---

## 3. Reader search input — dark background, light text

### What changed
- **New drawable: `app/src/main/res/drawable/reader_search_input_bg.xml`** —
  a rounded rectangle using `reader_chrome_surface` (plum `#503A65`) as the
  solid fill, a 1dp mint (`reader_chrome_accent`) stroke, and 10dp corners.
  This is the reader-palette-aligned dark input background.
- **`app/src/main/res/layout/activity_reader.xml`** — the `searchEdit`
  `EditText` background changed from `@drawable/cover_frame` (which was a light
  mint `#E0F0EA` solid) to `@drawable/reader_search_input_bg`. The text color
  was already `reader_chrome_text` (white) and the hint was already
  `reader_chrome_text_muted`, so now light text sits on a dark field — no
  clash, readable on every reading theme.

`bottom_sheet_search.xml` was **not** changed because it is unreferenced by any
layout inflater or Kotlin/Java code (verified with a project-wide grep) — it is
dead layout that is never shown.

---

## 4. Dark theme — dark inline-styled text now forced to light ink

### Root cause
Some EPUBs hard-code a text color on individual elements, e.g.
`<p style="color:#333333">`. The reader's CSS only set
`body { color: ink !important }` (plus `a` and `h1`–`h6`), which sets the
*inherited default*. An explicit color declared directly on a descendant wins
by CSS specificity over an inherited value — so that dark text stayed dark on
the dark (black) page and became invisible.

### What changed
**`ReaderActivity.kt`** — `buildReaderCss()` now appends an extra dark-mode-only
CSS block (via a new `darkTextOverride(theme, ink)` helper) **after** the main
`</style>`:
```css
body, body * { color: <ink> !important; }
```
- Applied **only** when the reading theme is DARK. Light and sepia books are
  untouched.
- `body *` forces the ink color onto **every** descendant element, including
  inline-styled `<p style="color:#333">` — because a `!important` rule
  overrides any non-`!important` inline declaration regardless of specificity.
- Images are not affected (`color` does not apply to `<img>`/`<svg>`).
- This is a reader-controlled color mode, matching what most dark-mode EPUB
  readers do.

### Limit (acceptable for this patch)
Inline `style="color:#333 !important"` (with its own `!important`) would still win
— that is rare in real books. If you ever encounter it, the next step would be a
luminance-based JavaScript recolor pass injected after `onPageFinished`; that is
deliberately avoided here because it can flash, runs after layout, and is hard
to keep in sync with the offscreen measurement WebView. See
`FUTURE_UPDATES_GUIDE.md` → "Dark theme recolor" for how to add it if needed.

---

## 5. Clean, instant scroll-to-top on every view switch

### What was wrong
The Patch 9 fix set a `scrollToTopOnNextContent` flag and ran
`scrollToPosition(0)` inside the `submitList` commit callback. That callback
fires **after** DiffUtil has already computed and dispatched the diff, so the
RecyclerView had already laid out the new items at the *restored* scroll offset
and animated the item inserts/removes for a frame before the jump to 0 landed.
Depending on grid column count this looked different:
- **List / grid-of-2 / grid-of-4**: leftover scroll offset from the previous
  view → a visible "fast scroll" jump to the top.
- **Grid-of-3 → Library**: looked okay (fade-in from top), but
  **Currently Reading**: grid of 3: landed in the middle of the screen when the
  few currently-reading books didn't fill it, so the user had to scroll up.

### What changed
**`MainActivity.kt`** — `bindBookAdapter`, `reconfigureAdapter` (grid ↔ list /
column-count change), and `bindRowAdapter` (Authors / Series) now take a **hard,
non-animated reset path** whenever a top-reset is wanted:
1. `recycler.stopScroll()` — cancel any in-flight fling.
2. Save the current `itemAnimator`, then set `itemAnimator = null` — disables
   DiffUtil item move/fade animations for the swap.
3. Create a **fresh** `BookAdapter` (or `RowAdapter`) with
   `stateRestorationPolicy = PREVENT_WHEN_EMPTY` — so no saved scroll state is
   restored and the new list is laid out from position 0 from the start.
4. Create a **fresh** `LayoutManager` (GridLayoutManager or LinearLayoutManager)
   — a new layout manager has no saved scroll state, so it starts at 0.
5. `(lm).scrollToPositionWithOffset(0, 0)` **before** `submitList` — pins the
   pending scroll to the top.
6. `submitList(list) { recycler.post { scrollToPositionWithOffset(0, 0);
   itemAnimator = previousAnimator } }` — one final pin after layout, then
   restore animations for normal scrolling afterward.

Because the adapter is fresh and the layout manager is fresh, there is no diff
to animate and no saved offset to restore — the new list simply appears at the
top, instantly and cleanly, in every grid mode. This is consistent across list,
grid-of-2, grid-of-3, and grid-of-4.

### Where the reset flag is set
- `selectDrawer` (drawer tap to any shelf view).
- `showSortDirectionDialog` (sort order change — a "different layout" event).
- `showScanResult` Snackbar "Show" action (after a rescan).
- `reconfigureAdapter` (grid ↔ list / column-count change) — always resets,
  since a layout-mode change is itself a reset.
- Author/Series row click → `viewModel.openDetail(name)` (entering a detail
  book list).
- Back / toolbar-home / options-menu-home from a detail view →
  `viewModel.clearDetail()` (returning to the Authors/Series list).

### Why no flag leakage
`viewModel.setView()` always assigns `_view.value` (even for the same view),
which fires the `_trigger` MediatorLiveData → `content` re-emits →
`bindBookAdapter`/`bindRowAdapter` runs and consumes the flag on the same
switch. The flag is consumed **immediately** at the top of the reset path (set
to `false` before the adapter swap), so it can never leak into a later,
unrelated content update.

---

## Files changed in this patch
| File | Change |
|---|---|
| `app/build.gradle.kts` | `versionCode = 10`, `versionName = "1.10-patch10"` |
| `app/src/main/res/values/themes.xml` | Black system bars on all 3 themes |
| `app/src/main/res/layout/activity_main.xml` | Black DrawerLayout root |
| `app/src/main/res/layout/activity_reader.xml` | Reader search input bg → `reader_search_input_bg` |
| `app/src/main/res/drawable/reader_search_input_bg.xml` | **NEW** dark rounded input drawable |
| `app/src/main/java/com/epubreader/app/ReaderActivity.kt` | Black bars/root in `applyWindowTheme`; dark-text CSS override in `buildReaderCss` |
| `app/src/main/java/com/epubreader/app/MainActivity.kt` | Hard non-animated scroll-to-top reset in `bindBookAdapter` / `reconfigureAdapter` / `bindRowAdapter`; flag set on detail open/return paths |

## Build / test status
- `./gradlew assembleDebug` → **BUILD SUCCESSFUL**. Signed debug APK is 7.7MB
  on disk.
- `./gradlew testDebugUnitTest` → **18 / 20 pass**. The 2 failures
  (`EpubParserTest.searchesRealEpub`, `EpubParserTest.parsesRealEpub`) are
  **pre-existing** from Patch 8/9: they load a test fixture EPUB that is not
  checked into the repo (`java.nio.file.NoSuchFileException`). They are
  unrelated to any Patch 10 change.
