# — The Livre Magicae Patch 20 Maintenance Guide 

This guide documents Patch 20 (versionCode <mark>21,</mark> versionName <mark>1.20-patch20)</mark> and gives you the patterns to maintain the app and ship future patches confidently. It is written so you (or anyone helping you) can make changes, rebuild, and release without reverse-engineering the project. 

## 1. What changed in Patch 20 

Patch 20 is intentionally small and surgical. Only four things changed relative to Patch 19; every other screen, overlay, animation, and the EPUB engine are untouched. 

|#|Change|File(s)|
|---|---|---|
|1|Removed the prev/next chevron row from the<br>reader bottom bar;the timeline seeker now<br>stands alone|app/src/main/res/layout/activity_reader.xml|
|2|Removed the dead<br>btnPrev /<br>btnNext<br>listeners and the<br>tvChapterInfo text<br>write from code|app/src/main/java/com/epubreader/app/ReaderActivity.kt|
|3|Search overlay:brought the header down to<br>toolbar height and made the search-box<br>le�/right spacing symmetric|app/src/main/res/layout/activity_reader.xml<br>(<br>searchOverlay)|
|4|Restored<br>android:scrollbars="vertical"<br>on the main bookshelf RecyclerView<br>(regression from Patch17)|app/src/main/res/layout/activity_main.xml|
|—|Version bump|app/build.gradle.kts →<br>versionCode21,<br>versionName<br>"1.20-patch20"|



Because the project's root build files were missing from the Patch 19 zip, Patch 20 also restores the full project skeleton (these were not in Patch 19): 

<mark>build.gradle.kts</mark> (root plugin versions) 

- <mark>settings.gradle.kts</mark> 

- <mark>gradle.properties</mark> 

- <mark>.github/workfows/android-ci.yml</mark> and <mark>.github/workfows/release.yml</mark> 

- <mark>scripts/build.sh, check_syntax.sh</mark> , <mark>validate.sh, release.sh</mark> 

- <mark>README.md</mark> 

## Why removing the chevron row is safe 

The "page X / Y" number that used to sit between the two chevrons is still shown by the persistent page indicator ( <mark>tvPageIndicator</mark> ) — the faint number that appears above the nav bar while you're reading. The seek bar + the percentage label remain in the bottom bar, so you keep full position feedback. The <mark>turnPage(forward)</mark> function is unchanged and is still wired to tap-le� / - — tap right / swipe gestures, so page turning is fully intact only the two on-screen arrow buttons are gone. 

## 2. The spacing gold standard (single source of truth) 

All app-wide spacing lives in <mark>app/src/main/res/values/dimens.xml.</mark> Edit a value there once and it changes everywhere it's referenced. This is " " the key to keeping every screen in sync without hunting through layouts. 

The most important dimensions: 

|Dimension|Value|Used by|
|---|---|---|
|app_screen_edge_h|16dp|Main-app top bar,list screens|
|app_chrome_padding_h|10dp|Reader chrome rows,overlay title rows|
|app_chrome_padding_v|6dp|Vertical padding inside a chrome bar|
|app_reader_icon_inset|10dp|Glyph inset inside a44dp icon button→ 20dp keyline|
|app_row_content_padding_h|20dp|Full-width list rows(TOC,search results,reader settings content)|
|app_grid_edge_h|10dp|Book RecyclerView edge padding|



The 20dp keyline. A 44dp icon button with a 24dp glyph, placed in a 10dp chrome container, lands the glyph's le� edge at 20dp from the screen edge. That 20dp is the app's visual keyline — reader content, overlay titles, and the reader-settings content all align to it. When you adjust spacing, aim to keep content on this keyline so screens stay consistent. 

## 3. Reader bottom bar — structure and how to change it 

The bottom bar <mark>(@id/bottomBar</mark> in <mark>activity_reader.xml)</mark> now contains, top to bottom: 

1. <mark>SeekBar @id/seekChapter</mark> — the timeline seeker (full width) 

2. A row with <mark>tvPercent</mark> (le�) and <mark>tvAddBookmark</mark> (right) 

If you ever want to re-add prev/next page buttons, re-insert a horizontal <mark>LinearLayout</mark> above the <mark>SeekBar</mark> containing two <mark>ImageButton</mark> s styled 

<mark>@style/EpubReaderIconButton</mark> with <mark>ic_chevron_left</mark> / <mark>ic_chevron_right,</mark> then re-wire them in <mark>ReaderActivity.kt:</mark> 

binding.btnPrev.setOnClickListener { turnPage(false) } binding.btnNext.setOnClickListener { turnPage(true) } 

<mark>(turnPage</mark> already exists and is shared with the gesture handlers, so no other code is needed.) 

## 4. Search overlay alignment — the math 

The search overlay header <mark>(@id/searchOverlay</mark> in <mark>activity_reader.xml</mark> ) is now: 

<mark>android:minHeight="?attr/actionBarSize"</mark> — matches the toolbar height used by Reader Settings (the gold standard) and the other full-screen activities, so the search box + back arrow sit at the same vertical position as a toolbar. 

- <mark>paddingTop="@dimen/app_reader_icon_inset"</mark> (10dp) — gives the box a little breathing room below the status bar, matching the reader chrome rhythm. 

- The <mark>EditText</mark> uses <mark>layout_marginStart="@dimen/app_reader_icon_inset"</mark> (10dp) and no <mark>layout_marginEnd,</mark> so the right gap of the box equals the container padding (10dp) — which equals the le� gap before the back arrow (10dp). The three gaps (le�-of-arrow, between-arrow-and-box, right-of-box) are all 10dp. 

If the box looks too close to or far from the back arrow on a given device, tweak <mark>app_reader_icon_inset</mark> in <mark>dimens.xml</mark> — it's the single knob. 

## 5. Scrollbars 

<mark>android:scrollbars="vertical"</mark> is now present on the three primary lists, so they behave consistently: 

- Main bookshelf — <mark>activity_main.xml @id/recycler</mark> (restored in Patch 20) 

- Search screen — <mark>activity_search.xml</mark> 

- Reader Settings — <mark>activity_reader_settings.xml</mark> (NestedScrollView) 

Item-row lists (drawer, TOC, search results, bookmarks) intentionally use <mark>overScrollMode="never"</mark> or no scrollbar for a cleaner look — don't add scrollbars there unless you want them everywhere. 

## 6. Build & toolchain (exact versions) 

|Component|Version|
|---|---|
|Android Gradle Plugin|8.7.2|
|Kotlin|2.0.20|
|KSP|2.0.20-1.0.25|
|Gradle(wrapper)|8.9|
|JDK|17 (Temurin)|



Component Version compileSdk / targetSdk 35 minSdk 24 

These are pinned in <mark>build.gradle.kts</mark> (root, plugin versions) and 

<mark>gradle/wrapper/gradle-wrapper.properties</mark> (Gradle distribution). To bump any of them, see Section 9 and always keep AGP ↔ Kotlin ↔ KSP ↔ Gradle compatibility in sync (KSP's version string embeds the Kotlin version, e.g. <mark>2.0.20-1.0.25</mark> means Kotlin 2.0.20). 

## Local build (no Android Studio needed) 

export JAVA_HOME=/path/to/jdk-17 export ANDROID_HOME=/path/to/android-sdk $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \ 

"platform-tools" "platforms;android-35" "build-tools;35.0.0" 

# from the project root: ./scripts/build.sh          # debug APK  -> app/build/outputs/apk/debug/the-livre-magic ./scripts/check_syntax.sh  # fast Kotlin compile gate (no APK) 

./scripts/validate.sh      # clean + compile + unit tests + APK + permission check 

./scripts/release.sh       # release APK -> release/the-livre-magicae-release.apk 

<mark>local.properties</mark> (the <mark>sdk.dir=</mark> line) is not committed — it's in ' <mark>.gitignore.</mark> Create it locally only if Gradle can t find the SDK from <mark>ANDROID_HOME</mark> . 

## CI (GitHub Actions) 

- <mark>android-ci.yml</mark> — runs on every push/PR. Sets up JDK 17 + Gradle, 

- verifies the Android SDK has platform 35 + build-tools 35.x, runs the Kotlin compile + resource merge, builds the debug APK, and uploads it as an artifact. It also asserts the APK declares no INTERNET permission. 

- <mark>release.yml</mark> — runs when you push a tag like <mark>v1.20.</mark> Builds a release 

- APK and publishes a GitHub Release with the APK attached. Debug-signed by default; see the commented steps in the workflow to sign with your own key. 

No secrets are required for the debug/CI build. 

## 7. Bumping the version for the next patch 

In <mark>app/build.gradle.kts:</mark> 

versionCode = 22 // increment by 1 each release versionName = "1.20-patch21" // or your naming scheme 

<mark>versionCode</mark> must always increase (Android uses it to detect upgrades). <mark>versionName</mark> is the human-readable string shown in the app. 

## 8. How to make a layout change (the repeatable pattern) 

1. Decide the spacing in <mark>dimens.xml</mark> first. Prefer an existing dimension ' 

over a hardcoded dp. Add a new one only if it s a genuinely new spacing concept. 

2. Edit the layout XML. Reference <mark>@dimen/...</mark> names, not literal values. 

3. If you remove a view that has an <mark>@+id,</mark> also remove every code reference to it. With view binding enabled, an <mark>@+id</mark> that's referenced in Kotlin but missing from the XML is a compile error — so the build will catch it for you. Run <mark>./scripts/check_syntax.sh</mark> a�er layout edits to catch this fast. 

4. Build the APK <mark>(./scripts/build.sh)</mark> and install on a device to verify. 

5. Bump the version (Section 7). 

## 9. Upgrading the toolchain (do this carefully) 

When you want to move to a newer AGP / Kotlin / Gradle, keep this order: 

1. Pick the new Kotlin version. 

1. Pick the matching **KSP** version (`<kotlin>-<ksp>`, e.g. for Kotlin 2.0.20 

it's <mark>2.0.20-1.0.25)</mark> . Check the KSP releases on GitHub for the matching string. 

1. Pick an AGP version that Kotlin supports and that supports your <mark>compileSdk</mark> (35 here). AGP's minimum Gradle version must be ≤ your wrapper Gradle version (8.9). 

2. Update both the root <mark>build.gradle.kts</mark> (AGP / Kotlin / KSP lines) and, if needed, <mark>gradle/wrapper/gradle-wrapper.properties</mark> (Gradle distribution URL). 

3. Run <mark>./scripts/validate.sh</mark> end to end before committing. 

## 10. Known items to be aware of 

- Unit tests need a real sample EPUB (and the release workflow runs them). <mark>EpubParserTest</mark> reads a book from the hardcoded path 

- <mark>/home/user/workspace/sample.epub</mark> . The bundled test resource <mark>app/src/test/resources/epub/Pride and Prejudice.epub</mark> is a truncated stub (corrupt zip) and will not satisfy the parser test, so those two tests fail with <mark>java.util.zip.ZipException: zip END header not found.</mark> 

- What this means for CI: <mark>android-ci.yml</mark> does not run unit tests — it only compiles + builds the APK — so pushes and PRs stay green. But <mark>release.yml</mark> does run <mark>testDebugUnitTest</mark> , so a tag-triggered release build will fail until a valid <mark>sample.epub</mark> exists at 

- <mark>/home/user/workspace/sample.epub</mark> (in the release environment) or the test 

path is adjusted. To run the parser tests locally, drop a real EPUB at that path. This is pre-existing from earlier patches and is not affected by anything in Patch 20. 

' APK output filename. <mark>app/build.gradle.kts</mark> renames every variant s output to <mark>the-livre-magicae.apk</mark> . The CI upload step and the <mark>validate.sh</mark> / <mark>build.sh</mark> scripts expect that exact filename. Patch 20 also fixed <mark>release.yml</mark> , <mark>release.sh</mark> , and <mark>README.md</mark> so the release paths 

use <mark>the-livre-magicae.apk</mark> too (they previously said <mark>app-release.apk</mark> / 

<mark>app-debug.apk,</mark> which would have failed because of the rename). Keep these in sync if you ever rename the output. 

## 11. Patch 20 file map (quick reference) 

Changed source files (vs Patch 19): 

app/build.gradle.kts                                          # version bump app/src/main/res/layout/activity_reader.xml                  # bottom bar + search over app/src/main/res/layout/activity_main.xml                   # scrollbar restored app/src/main/java/com/epubreader/app/ReaderActivity.kt       # removed chevron refs 

Restored project skeleton (was missing from Patch 19 zip): 

build.gradle.kts, settings.gradle.kts, gradle.properties, README.md .github/workflows/android-ci.yml, .github/workflows/release.yml scripts/build.sh, scripts/check_syntax.sh, scripts/validate.sh, scripts/release.sh 

Build output: <mark>app/build/outputs/apk/debug/the-livre-magicae.apk</mark> 

