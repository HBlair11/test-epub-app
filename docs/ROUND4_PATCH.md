# Round 4 Patch — Apply Instructions

This patch fixes the reader pagination (horizontal page-by-page with swipe + page-turn
animation), adds double-tap-back-to-exit, makes the folder button context-aware,
adds pull-to-refresh folder scanning, lets Library import multiple EPUBs, and zooms
out the app icon.

## Files in this patch

- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — pagination rewrite, swipe, page-turn animation
- `app/src/main/java/com/epubreader/app/MainActivity.kt` — back-to-exit, FAB visibility, folders view, pull-to-refresh
- `app/src/main/java/com/epubreader/app/data/PrefsManager.kt` — persisted selected folder URI
- `app/src/main/java/com/epubreader/app/epub/EpubImporter.kt` — reports newly-added books
- `app/src/main/res/values/strings.xml` — new strings
- `app/src/main/res/drawable-nodpi/ic_launcher_foreground_image.png` + all `mipmap-*/ic_launcher*.png` — zoomed-out icon (binary)

## Option A — Apply with `git apply` (recommended)

1. Put `caesura-round4.patch` at the **root of your repo** (the folder containing
   `settings.gradle.kts`).
2. From the repo root:
   ```bash
   git apply --binary caesura-round4.patch
   ```
   - `--binary` is required because the patch includes regenerated PNG icons.
   - If it fails with "patch does not apply", your tree has local edits that conflict.
     Run `git apply --binary --3way caesura-round4.patch` to attempt a merge, or
     stash your changes (`git stash`), apply, then `git stash pop`.

3. If the patch went in cleanly, commit it:
   ```bash
   git add -A
   git commit -m "Round 4: reader pagination, back-to-exit, folder FAB, pull-to-refresh, icon zoom-out"
   ```

### Verifying it applied
```bash
git apply --stat caesura-round4.patch        # shows what files change (dry run)
git apply --check --binary caesura-round4.patch   # exits 0 if it will apply cleanly
```

## Option B — Apply as a commit (preserves history)

```bash
git am --binary < caesura-round4.patch
```
`git am` applies the patch as a commit. Use this if your repo is a git repo and the
patch was generated from a clean tree (it was).

## Build after applying

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug --console=plain --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or, from the helper scripts in the repo:
```bash
./scripts/build.sh
```

## What you'll see after installing

- **Reader**: tap right/left edge or swipe left/right to turn pages (smooth slide
  animation). Chapters no longer skip on tap — you page through, and only advance
  chapters at the very last/first page. Previous chapter opens on its last page.
  No vertical scroll inside a chapter.
- **Back button**: first press shows "Press back again to exit"; second press exits.
  (Still closes the drawer / detail first if either is open.)
- **Folder button**: the floating button now shows only in Library (a "+" to import
  one or more EPUB files) and in Folders (folder icon → Select / Scan / Remove).
  Hidden on every other view.
- **Pull to refresh**: swipe down on any book view to re-scan your selected folder.
  Snackbar reads "Scan completed, N new book(s) found" with a **Show** button, or
  "Scan completed, no new books found".
- **Icon**: the book-stack logo is zoomed out with a white border so it isn't
  cropped by the launcher mask.
