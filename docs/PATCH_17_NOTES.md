# — Patch 17 New Themes & Reader Settings Screen, - Page Turn Animation 

Version 1.18-patch17 (versionCode 18), package <mark>com.epubreader.app.</mark> 

Built with JDK 17 / Gradle 8.9 / AGP 8.7.2 / Kotlin 2.0.20 / compileSdk 35 / minSdk 24. No new permissions. Still fully offline (no <mark>INTERNET)</mark> . 

This patch delivers the three requested changes and keeps every existing reader feature intact. It is a drop-in update: install over Patch 16 and existing reader preferences (theme, font, margins, etc.) carry over. 

## 1. What changed 

## Issue #1 — Reader settings are now a full screen (not a bottom sheet) 

- Before: <mark>ReaderSettingsSheet</mark> — a <mark>BottomSheetDialog</mark> that popped from the bottom. Sliders + a crowded row of toggle buttons meant a vertical scroll could accidentally nudge a value, and adding more options made the toggle row overflow. 

- Afer: <mark>ReaderSettingsActivity</mark> — a dedicated full-screen activity with the same dark reader chrome (eggplant background, white text, mint accents). 

   - Every numeric control is a +/− stepper <mark>((value) +</mark> ). No sliders, so a vertical scroll can never change a value. 

   - Every multi-option control is a dropdown <mark>(ExposedDropdownMenu)</mark> , which scales cleanly as you add more options instead of crowding a toggle row. 

   - The toggle rows (Top & bottom margin, Hyphenation, Page turn 

   - animation) now use the same caps-label + control layout as the steppers, so the whole screen reads as one consistent, readable list. 

### Setting order on screen: 

1. Theme (dropdown) 

2. Font Face (dropdown) 

3. Alignment (dropdown) — retained intentionally; see note below 

4. Font Size (stepper) 

5. Line Spacing (stepper) 

6. Margin (stepper) 

7. Top & Bottom Margin (switch) 

8. Hyphenation (switch) 

9. Page Turn Animation (switch) — new in Patch 17 

Why Alignment is still here: the requested order list omitted Alignment, but removing it would be a feature regression and you asked to keep functionality intact and not stray from the original app. It is kept as a dropdown, placed right a�er Font Face where it logically belongs. If you ever want to hide it, see §3.2. 

### Behavior change to know about: settings are applied once on return to the 

reader, not live on every tap. The old sheet re-applied (and re-measured page counts) on every slider drag; the new screen writes each change to prefs as you make it, then returns <mark>RESULT_OK</mark> when you close it (back arrow or hardware back), and <mark>ReaderActivity.applySettingsAndReload()</mark> applies everything in one pass. This is faster and avoids re-measuring page counts on every +/− tap. 

## Addition #1 — Three new themes + renamed originals (single source of truth) 

- Ivory (was Light) — white page, black ink 

- Nordic Eco — <mark>#E8EFE9</mark> pale sage bg / <mark>#242B27</mark> deep forest ink (new) 

- Alabaster (was Sepia) — cream bg / warm dark ink 

- Candlelight — <mark>#E8D3A7</mark> muted amber bg / <mark>#2B1A0A</mark> espresso ink (new) 

- Onyx (was Dark) — black page, white ink 

- Midnight Slate — <mark>#1A1B1E</mark> slate bg / <mark>#D1D5DB</mark> silver ink (new) 

Dropdown order: Ivory → Nordic Eco → Alabaster → Candlelight → Onyx → Midnight Slate. 

Existing installs are migrated automatically: a stored <mark>light/sepia</mark> / <mark>dark</mark> is rewritten to <mark>ivory/alabaster/onyx</mark> on first read (same colors, new name). 

## Addition #2 — Page-turn animation toggle + book-like slide 

- New Page Turn Animation switch in reader settings (default ON, preserving the pre-Patch-17 animated experience). 

- ON: the old page slides out horizontally in the turn direction (forward → 

- off the le� edge, back → off the right edge) with a short alpha fade — reads like turning a physical page. Duration is 340 ms (up from the old 220 ms crossfade) so the motion feels deliberate, not flickery. 

- OFF: pages change instantly with no animation. 

- This is a single-snapshot slide, not a 3D page-curl library — deliberately 

- simple to keep the app smooth, dependency-free, and easy to maintain (see §4). 

## 2. The theme system — single source of truth 

File: <mark>app/src/main/java/com/epubreader/app/ui/ReaderThemes.kt</mark> 

Every reading theme is defined exactly once as a <mark>ReaderTheme</mark> data class: 

data class ReaderTheme( 

val id: String, // persisted to SharedPreferences — NEVER rename once val displayNameRes: Int, // R.string.<name> shown in the dropdown + main-app la val bgHex: String, // content-area background (the "page") val inkHex: String, // body text / link color val needsInkOverride: Boolean, // force ink through all elements + flatten bg (see ) 

The 6 themes live in the <mark>ALL</mark> list (companion object). The order of <mark>ALL</mark> is the order shown in the Theme dropdown. Three consumers all read from this one registry, so a single edit propagates everywhere: 

|Consumer|What it reads|File|
|---|---|---|
|Settings<br>dropdown|ReaderTheme.ALL (order+display names)|ui/ReaderSettingsActivity.kt|
|Reader|||
|CSS+<br>window|ReaderTheme.byId(prefs.theme).bgColor/inkHex|ReaderActivity.readerColors()|
|colors|||
|Main-app<br>settings<br>label|ReaderTheme.byId(prefs.theme).displayNameRes|MainActivity.readerThemeLabel()|
|Ink-|||
|through-<br>override|ReaderTheme.byId(theme).needsInkOverride|ReaderActivity.darkTextOverride()|
|decision|||



### <mark>needsInkOverride</mark> is true for every non-Ivory theme. When true, the reader 

forces the ink color through every descendant element AND flattens hard-coded <mark>background-colors</mark> to transparent — the standard reader tradeoff for tinted/dark pages (a hard-coded white box on a cream/slate page would otherwise show as a bar). It's unnecessary for a pure-white page, so Ivory is <mark>false</mark> . 

## How to edit themes 

- Recolor a theme: change <mark>bgHex/inkHex</mark> in <mark>ReaderThemes.kt.</mark> Done — no other file needs editing. 

- Rename a theme (display name only): change the string in 

- <mark>values/strings.xml</mark> (e.g. <mark>theme_ivory)</mark> . The persisted id stays the same, so no migration is needed and existing installs keep their selection. 

- Reorder the dropdown: reorder the entries in the <mark>ALL</mark> list. 

- Add a theme: add a new <mark>ReaderTheme(...)</mark> entry, add its <mark><string></mark> to 

- <mark>strings.xml,</mark> add its <mark>id</mark> constant to <mark>PrefsManager.Theme</mark> , and add it to 

- <mark>ALL</mark> in the position you want. If the new theme has a tinted or dark background, set <mark>needsInkOverride = true</mark> . (See §3.1 for the full checklist.) 

- Migrate an old id: if you ever rename a persisted <mark>id</mark> , add a case to <mark>ReaderTheme.migrate()</mark> so existing installs keep their colors. 

## 3. The settings screen — how to extend it 

Layout: <mark>app/src/main/res/layout/activity_reader_settings.xml</mark> 

Code: <mark>app/src/main/java/com/epubreader/app/ui/ReaderSettingsActivity.kt</mark> 

Theme: <mark>Theme.EpubReader.Settings</mark> (in <mark>values/themes.xml</mark> ) — static dark 

chrome, registered in <mark>AndroidManifest.xml.</mark> 

## 3.1 Add a new theme (end-to-end checklist) 

1. <mark>ReaderThemes.kt</mark> — add a <mark>ReaderTheme</mark> constant + include it in <mark>ALL</mark> . 

2. <mark>strings.xml</mark> — add <mark><string name="theme_myname">My Name</string></mark> . 

3. <mark>PrefsManager.kt</mark> — add <mark>const val MYNAME = "myname"</mark> to <mark>object Theme.</mark> 

4. Build. The dropdown, reader CSS, and main-app label all pick it up automatically. 

## 3.2 Add a new setting row 

The layout is a vertical <mark>LinearLayout</mark> inside a <mark>NestedScrollView</mark> . Each block is 

a caps <mark>TextView</mark> label <mark>(@style/ReaderSettingLabel)</mark> followed by the control. 

- Dropdown: copy the <mark>Theme</mark> block — a <mark>TextInputLayout</mark> 

- <mark>(Widget.MaterialComponents.TextInputLayout.OutlinedBox.ExposedDropdownMenu)</mark> containing a <mark>MaterialAutoCompleteTextView.</mark> Bind it in code with the <mark>bindDropdown()</mark> helper (stable id→position mapping, so renames never corrupt the persisted value). 

- Stepper: copy the <mark>Font Size</mark> block — a row with a <mark>ReaderStepperBtn</mark> 

- <mark>(app:icon="@drawable/ic_minus"</mark> / <mark>ic_add)</mark> on each side and a 

- <mark>ReaderStepperValue TextView</mark> in the middle. Clamp to the min/max constants in <mark>PrefsManager.Companion.</mark> 

- Switch: copy the <mark>Hyphenation</mark> block — a <mark>SwitchCompat</mark> with <mark>"</mark> 

- <mark>style="@style/ReaderSwitchRow</mark> and a summary line. 

For any new setting, add a field + getter/setter to <mark>PrefsManager</mark> , then read it in <mark>ReaderSettingsActivity.onCreate</mark> and (if it affects rendering) apply it in 

<mark>ReaderActivity.buildReaderCss()</mark> / <mark>applyWindowTheme().</mark> 

## 4. The page-turn animation — how it works & how to tune it 

Files: <mark>ReaderActivity.kt</mark> — <mark>capturePageSnapshot(forward)</mark> , 

<mark>dismissPageSnapshot(forward), clearSnapshot(v), turnPage(forward)</mark> , <mark>performPageTurn(forward)</mark> , <mark>PAGE_TURN_DURATION_MS</mark> . 

The reader renders pages in a <mark>WebView.</mark> To transition, the old page is drawn into a bitmap on a full-screen <mark>ImageView</mark> overlay ( <mark>snapshotView)</mark> that sits above the WebView; the new page is positioned in the WebView beneath it; then the snapshot is removed to reveal the new page. 

- Animation ON: <mark>dismissPageSnapshot</mark> slides the snapshot out horizontally <mark>(translationX</mark> → ±width) with an alpha fade over <mark>PAGE_TURN_DURATION_MS.</mark> Forward turns slide le�, backward turns slide right. 

- Animation OFF: <mark>dismissPageSnapshot</mark> calls <mark>clearSnapshot()</mark> immediately 

- (same cleanup path, no animation). 

## Tuning 

- Speed: change <mark>ReaderActivity.PAGE_TURN_DURATION_MS</mark> (one number, app-wide). Bump it for a slower, more "page-like" turn; lower it for snappier navigation. The page-turn throttle ( <mark>turnPage)</mark> is set to <mark>duration + 40 ms</mark> so two rapid taps can never stack two animations. 

- On/off default: <mark>PrefsManager.pageTurnAnimation</mark> defaults to <mark>true</mark> . Change the default in the <mark>get()</mark> if you want it off out of the box. 

## - ' Bug safety measures built in (why this won t introduce visual glitches) 

- Direction is remembered in <mark>snapshotForward</mark> when a snapshot is captured, so every dismiss path (in-chapter turn, chapter cross, TOC/seeker jump, restore) reuses the correct slide direction even if it doesn't pass it explicitly. 

- A token ( <mark>snapshotAnimToken)</mark> invalidates a stale dismiss end-action when a — 

- newer capture supersedes an in-flight slide so an older cleanup can never recycle a newer bitmap. <mark>capturePageSnapshot</mark> cancels any running animation and recycles the previous bitmap only a�er installing the new one. 

- The instant path shares the same cleanup ( <mark>clearSnapshot</mark> ) as the animated path, so toggling the setting on/off mid-session never leaks a bitmap or leaves a transform half-applied <mark>(translationX/alpha</mark> are always reset). 

- No new dependencies. The animation is plain <mark>View.animate()</mark> ; no 3D/curl library was added, so the app stays small, offline, and easy to maintain. A single horizontal slide + fade reads clearly as "turning a page" without the visual overwhelm or maintenance cost of a full curl effect. 

## 5. Files changed in this patch 

|File|Change|
|---|---|
|app/build.gradle.kts|versionCode17 → 18,versionName→<br>1.18-patch17|
|AndroidManifest.xml|Registered<br>ReaderSettingsActivity<br>(<br>Theme.EpubReader.Settings)|
|ui/ReaderThemes.kt|NEW —single source of truth for all themes(colors,order,ink-<br>override)|
|ui/ReaderSettingsActivity.kt|NEW —full-screen settings activity(dropdowns+steppers+<br>switches)|
|res/layout/activity_reader_settings.xml|NEW —settings screen layout|



|File|Change|
|---|---|
|res/values/themes.xml|Added<br>Theme.EpubReader.Settings|
|res/values/styles.xml|Added stepper/switch-row/block styles|
|res/values/strings.xml|New theme display names,settings labels,stepper a11y strings,<br>page-turn strings|
|data/PrefsManager.kt|New theme ids+legacy migration;new<br>pageTurnAnimation pref|
|ReaderActivity.kt|Theme registry lookup;slide page-turn animation+token guard;<br>throttle bump;<br>ActivityResultContractsettings<br>launch;deleted sheet usage|
|MainActivity.kt|readerThemeLabel() reads from the registry|
|ui/ReaderSettingsSheet.kt|DELETED (replaced by the activity)|
|res/layout/dialog_reader_settings.xml|DELETED (replaced by<br>activity_reader_settings.xml)|



## 6. Build & verify 

# One-time env (see LOCAL_BUILD_GUIDE.md for the full recipe): export JAVA_HOME=$HOME/workspace/build-env/jdk-17.0.12+7 export ANDROID_HOME=$HOME/workspace/build-env/android-sdk export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platfo 

cd /path/to/the-livre-magicae chmod +x gradlew ./gradlew assembleDebug --no-daemon --console=plain 

APK output: <mark>app/build/outputs/apk/debug/the-livre-magicae.apk</mark> 

Verify (no <mark>INTERNET</mark> permission, correct version, new classes present): 

AAPT=$ANDROID_HOME/build-tools/35.0.0/aapt2 

$AAPT dump badging the-livre-magicae.apk | grep -E "versionCode|versionName|package:" 

$AAPT dump permissions the-livre-magicae.apk        # only DYNAMIC_RECEIVER_NOT_EXPORT 

CI is unchanged — the GitHub Actions workflow ( <mark>android-ci.yml)</mark> builds the debug APK on every push/PR on <mark>ubuntu-latest</mark> with the pre-installed Android SDK and JDK 17. 

The two <mark>EpubParserTest</mark> unit tests require a <mark>sample.epub</mark> fixture at <mark>/home/user/workspace/sample.epub</mark> ; they fail only in environments without that fixture and are unrelated to this patch. All 13 <mark>EpubPageMapTest</mark> / <mark>ReaderPageMappingTest</mark> tests pass. 

