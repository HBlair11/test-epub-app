# The Livre Magicae — Patch 13 Release Notes

**Version:** 1.13-patch13 (versionCode 13)
**Package:** `com.epubreader.app` (display label "The Livre Magicae")
**Permissions:** only the auto-generated `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. No `INTERNET`.

Patch 13 fixes two reader rendering issues identified from a user-supplied EPUB
(*The Love Hypothesis* by Ali Hazelwood): dark-mode white bars over chapter
headings, and embedded `@font-face` fonts + inline images failing to load.

---

## Issue #1 — Dark-mode white bars over headings

**Symptom:** In dark mode, chapter headings (e.g. a "1" badge or an all-caps
title) that carry a light/white `background-color` rendered as solid white bars
that hid the heading text. Light mode was fine.

**Cause:** `ReaderActivity.darkTextOverride()` forces every element's text to
white in dark mode (`color: ink !important`) so dark-on-dark text survives, but
it did not touch `background-color`. A heading with a white `background-color`
kept that white box; the forced-white text disappeared into it → a white bar.

**Fix:** in dark mode, also force descendant backgrounds to transparent,
leaving `body`'s own dark page background (set in `buildReaderCss()`) intact:

```css
body, body * { color: <ink> !important; }
body * { background-color: transparent !important; }
```

`body *` targets only descendants of `body`, so `body` itself keeps its dark
background. Colored callout boxes also flatten in dark mode — the standard
dark-reader tradeoff. Light/sepia are untouched (the override returns `""`).

**File:** `ReaderActivity.kt` → `darkTextOverride()`.

---

## Issue #2 — Embedded fonts and the heart image not loading (Publisher font didn't help)

**Symptom:** "Chapter One" rendered as plain sans-serif instead of the book's
script font ("Emmascript MVB Std"); the small heart image and its gray box
were missing; the body used a fallback face. Calibre / the other app rendered
it correctly. Selecting the **Publisher** font did not fix it.

**Root cause:** The book's CSS files *were* loading and applying (that's why
"Chapter One" was 2em with a border and "HYPOTHESIS" was small/italic) — but the
embedded `@font-face` **font files** (`fonts/*.otf`) and the inline **heart
image** (`images/heart.jpeg`) were not loading, so named fonts fell back and
the `<img>` failed.

The cause was in `EpubResourceResolver.intercept()`, which served **every**
resource — including binary fonts and images — with a hard-coded **`"UTF-8"`
character encoding**:

```kotlin
WebResourceResponse(mimeTypeFor(decoded), "UTF-8", ByteArrayInputStream(bytes))
```

`"UTF-8"` is a *character* encoding. It is correct for the text CSS/HTML files
(which is why those loaded fine), but for **binary** resources the WebView
tried to character-decode the raw bytes, corrupting the font/image so it
silently failed to load. This book's only binary assets inside the reader were
the fonts and the heart image — exactly what broke.

This also explains why Publisher mode didn't help: it only stops the
`font-family` override; it does nothing about the underlying failure to
*load* the `@font-face` font files.

**Fix:** only attach a charset to **text** MIME types; pass `null` for binary
so the WebView treats the bytes as raw:

```kotlin
val mime = mimeTypeFor(decoded)
val charset = if (mime.startsWith("text/") ||
    mime == "application/xml" ||
    mime == "application/xhtml+xml" ||
    mime == "application/javascript") "UTF-8" else null
return WebResourceResponse(mime, charset, ByteArrayInputStream(bytes))
```

**File:** `EpubResourceResolver.kt` → `intercept()`.

### Temporary resolver logging (for confirmation)

Because the Android WebView cannot be run in the build sandbox, Patch 13 also
adds temporary `Log` lines tagged `[PATCH13-RES]` to `intercept()`:

- `[PATCH13-RES] OK entry=… mime=… charset=… bytes=…` for every resource served.
- `[PATCH13-RES] NOT FOUND decoded=… raw=… url=…` when a requested path does
  not match a ZIP entry.
- `[PATCH13-RES] no entry path for url=…` when the URL is not a virtual EPUB URL.

Filter logcat by the tag `EpubResourceResolver` (or the string `PATCH13-RES`)
to confirm the fonts (`fonts/00001.otf` …) and the heart (`images/heart.jpeg`)
resolve with `charset=null` and non-zero byte counts. If the fonts/heart still
do not render, the NOT FOUND / no-entry-path lines will show the exact failing
path so it can be nailed precisely. These log lines can be removed in a future
patch.

---

## How to verify on device

1. Install `The-Livre-Magicae-Patch-13.apk` over your existing install (your
   library and settings are preserved — same package, versionCode bumped).
2. Open *The Love Hypothesis*, Chapter One.
   - "Chapter One" should render in the **Emmascript script font** (not sans-serif).
   - The small **heart image** and its gray box should appear before "HYPOTHESIS".
   - Body text should use the book's **Avenir** face.
3. Switch to **Dark** reader theme and re-open the chapter.
   - The chapter headings should be **visible** (no solid white bars over them).
4. (Optional) `adb logcat -s EpubResourceResolver` to see the `[PATCH13-RES]`
   resource resolution lines.

---

## Build & deliverables

- **APK:** `The-Livre-Magicae-Patch-13.apk` (~6.9MB, versionCode 13)
- **Source:** `The-Livre-Magicae-Patch-13-source.zip` (200 files)
- **Build:** JDK 17 / Android SDK 35, `./gradlew assembleDebug`, verified via
  `aapt2 dump badging` (label `The Livre Magicae`, versionCode 13, no INTERNET).

## Files changed in Patch 13

| File | Change |
| --- | --- |
| `app/src/main/java/com/epubreader/app/ReaderActivity.kt` | `darkTextOverride()`: add `body * { background-color: transparent !important; }` in dark mode (Issue #1). |
| `app/src/main/java/com/epubreader/app/epub/EpubResourceResolver.kt` | `intercept()`: charset only for text MIME, `null` for binary; add `[PATCH13-RES]` logging (Issue #2). |
| `app/build.gradle.kts` | versionCode 12 → 13, versionName `1.12-patch12` → `1.13-patch13`. |
