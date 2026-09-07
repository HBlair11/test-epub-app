# — The Livre Magicae Patch 22 Maintenance Guide 

This guide documents Patch 22 (versionCode <mark>23,</mark> versionName <mark>1.22</mark> ). The version label was cleaned — the "patch" wording is gone. The internal app name and package name are unchanged. The output APK stays named <mark>the-livre-magicae.apk</mark> (as requested). 

All Patch 21 spacing work (the 20dp keyline across every screen, the two-row in-reader search overlay, the centered overlay titles, the restored scrollbar) is preserved — Patch 22 is small, surgical fixes to things you reported. 

## 1. What changed in Patch 22 

Four things, exactly as you reported: 

|#|Change|File(s)|
|---|---|---|
|1|Cleaned the version label and bumped the versionCode<br>(no"patch"/"debug"in the version name)|app/build.gradle.kts —versionCode<br>23,<br>versionName<br>"1.22"|
|2|Restored the page X/Y label inside the reader bottom bar<br>(it was dropped when the chevron row was removed in<br>Patch20)and gave the timeline seeker vertical breathing<br>room|activity_reader.xml (new<br>@id/tvPageInfo<br>above the SeekBar),<br>ReaderActivity.kt<br>(<br>setPageText()now updates both the bottom-bar label<br>and the persistent indicator)|
|3|Tapping the in-reader search back arrow now dismisses<br>the keyboard(was:keyboard stayed until you tapped the<br>nav-bar back button);opening the search overlay also<br>auto-shows the keyboard+cursor,mirroring the main<br>app's SearchActivity|ReaderActivity.kt (<br>showSearchOverlay(),<br>btnSearchBack listener)|
|4|Fixed the keep-screen-on setting being defeated when<br>the chrome was hidden—the screen now stays on for the<br>full extension while reading|ReaderActivity.kt (<br>toggleChrome())|



The output APK filename is unchanged: <mark>the-livre-magicae.apk</mark> . No scripts, CI workflow, or README references needed touching. 

## 2. The restored page X / Y label 

## The problem 

In Patch 20 the bottom-bar prev/next chevron row (which carried <mark>@id/tvChapterInfo)</mark> was removed. That row also held the "page X / Y" label, so the label went away with it. At the same time the timeline seeker ( <mark>@id/seekChapter)</mark> became the first thing in the bottom bar and sat cramped against the bar's top edge. 

The fix 

A new <mark>TextView @id/tvPageInfo</mark> was added to the top of <mark>@id/bottomBar</mark> , above the <mark>SeekBar:</mark> 

<TextView android:id="@+id/tvPageInfo" android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="center" android:paddingStart="@dimen/app_reader_icon_inset" android:paddingEnd="@dimen/app_reader_icon_inset" android:paddingBottom="@dimen/app_chrome_padding_v" android:textColor="?android:textColorSecondary" android:textSize="12sp" /> 

It is centered ( <mark>android:gravity="center"</mark> ), matching how the old <mark>@id/tvChapterInfo</mark> label sat centered between the prev/next chevrons. It uses the same 20dp keyline ( <mark>app_reader_icon_inset</mark> = 10dp, plus the bar's own 10dp edge = 20dp) and the same subtle secondary text tone as the old label, so it matches the bottom-bar design. Because it sits between the bar's top padding and the seeker, the seeker now has proper vertical breathing room. 

## How it gets its text 

<mark>ReaderActivity.setPageText(text)</mark> is the single place both labels are written: 

private fun setPageText(text: String) { binding.tvPageIndicator.text = text   // persistent faint indicator, shown while binding.tvPageInfo.text = text        // bottom-bar label, shown when chrome is v } 

<mark>updatePageIndicator()</mark> already called 

<mark>setPageText(getString(R.string.reader_page_of_pages, currentBookPage, total)),</mark> so both labels now show "page X / Y" (string <mark>reader_page_of_pages</mark> = <mark>" "%1$d / %2$d )</mark> . The two labels alternate: when the chrome is hidden the persistent <mark>tvPageIndicator</mark> shows; when the chrome is visible the bottom-bar <mark>tvPageInfo</mark> shows. Both are kept in sync from the one setter. 

## 3. Keyboard handling for the in-reader search overlay 

## The problem 

The in-reader search overlay ( <mark>@id/searchOverlay)</mark> opened and focused the <mark>EditText,</mark> but the back arrow <mark>(@id/btnSearchBack</mark> ) only hid the overlay — it did not dismiss the IME. So a�er typing, tapping the back arrow le� the keyboard on screen until you tapped the phone's nav-bar back button. The main 

app's SearchActivity handles this cleanly (it calls <mark>fnish()</mark> , which closes the IME automatically), which is why that one felt right. 

## The fix 

Two changes in <mark>ReaderActivity.kt:</mark> 

On open ( <mark>showSearchOverlay()</mark> , a�er the overlay is visible) — explicitly show the IME and focus the field, so the keyboard appears with the cursor the moment the overlay opens, exactly like SearchActivity: 

searchEdit?.requestFocus() 

val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager imm.showSoftInput(searchEdit, InputMethodManager.SHOW_IMPLICIT) 

The <mark>SHOW_SOFT_INPUT/showSoftInput</mark> call is made a�er 

<mark>binding.searchOverlay.visibility = View.VISIBLE</mark> so the IME reliably attaches to the now-visible field. 

On close <mark>(btnSearchBack</mark> listener) — clear focus and hide the IME before hiding the overlay, so tapping the back arrow dismisses the keyboard in one tap, mirroring the nav-bar back behavior: 

binding.btnSearchBack.setOnClickListener { searchEdit?.clearFocus() 

val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager imm.hideSoftInputFromWindow(searchEdit?.windowToken, 0) hideOverlays() } 

<mark>InputMethodManager</mark> is referenced by its fully-qualified type, so no new <mark>import</mark> was needed and there is no risk of an unused-import lint warning. 

## 4. Keep-screen-on fix (the real bug) 

## The problem 

There are two keep-screen-on mechanisms, and they were fighting each other: 

1. <mark>KeepScreenOnController (util/KeepScreenOnController.kt)</mark> — wired into <mark>MainActivity</mark> , <mark>ReaderActivity,</mark> and <mark>BookDetailsActivity</mark> . When the "keep screen on" setting is on, it sets <mark>FLAG_KEEP_SCREEN_ON</mark> on <mark>onResume()</mark> and re-arms it on every <mark>onUserInteraction(),</mark> keeping the screen awake for the system screen-off timeout + 10 minutes. It also releases the flag when the setting is off. 

2. <mark>ReaderActivity.toggleChrome()</mark> — when the reader chrome (top/bottom bar) is shown it adds <mark>FLAG_KEEP_SCREEN_ON</mark> (so the menu doesn't time out), and 

when the chrome is hidden it cleared the flag unconditionally. 

The bug: when "keep screen on" was on and you tapped to hide the chrome (the normal reading state), <mark>toggleChrome()</mark> cleared the flag that the controller had set. The screen then fell back to the system timeout and turned off, even though the setting was on. So while reading, "keep screen on" did nothing. 

## The fix 

<mark>toggleChrome()</mark> now only clears the flag if the user has not enabled keep screen on: 

if (chromeVisible) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else if (!prefs.keepScreenOn) { 

window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) 

} 

Chrome visible: always add the flag (menu stays awake). Unchanged. 

- Chrome hidden, keep-screen-on OFF: clear the flag (normal: screen can time out while reading). This is the original behavior, preserved. 

Chrome hidden, keep-screen-on ON: do nothing. The controller owns the flag now — it stays set, the screen stays awake for the full extension, and re-arms on every interaction. 

<mark>prefs.keepScreenOn</mark> is the same preference the controller and the settings switch already use ( <mark>PrefsManager.keepScreenOn</mark> ), so the two mechanisms are now consistent instead of fighting. 

This only affects the reader. The library ( <mark>MainActivity)</mark> and book details <mark>(BookDetailsActivity)</mark> have no <mark>toggleChrome()</mark> and were already correct — the controller handles them cleanly. 

## 5. Version label cleanup (naming) 

|Field|Before(Patch21)|A�er(Patch22)|
|---|---|---|
|versionCode|22|23 (incremented so it installs over Patch21)|
|versionName|"1.21-patch21"|"1.22" (clean—no"patch"/"debug")|
|App label(<br>app_namestring)|"The Livre Magicae"|"The Livre Magicae" (unchanged)|
|Application ID/package|com.epubreader.app|com.epubreader.app (unchanged)|
|Output APK filename|the-livre-magicae.apk|the-livre-magicae.apk (unchanged)|



<mark>versionCode</mark> was bumped from 22 to 23 so Patch 22 installs as an upgrade over Patch 21 (which was already published at <mark>versionCode</mark> 22). For the next release, bump <mark>versionCode</mark> to 24 and pick your <mark>versionName</mark> (e.g. 

<mark>"1.23"</mark> ). <mark>versionCode</mark> must always be greater than the previous published build so Android treats it as an upgrade. 

## 6. Build & toolchain (unchanged) 

|Component|Version|
|---|---|
|Android Gradle Plugin|8.7.2|
|Kotlin|2.0.20|
|KSP|2.0.20-1.0.25|
|Gradle(wrapper)|8.9|
|JDK|17 (Temurin)|
|compileSdk/targetSdk|35|
|minSdk|24|



## Local build 

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 export ANDROID_HOME=/home/user/android-sdk 

cd /home/user/workspace/patch20_src 

./gradlew assembleDebug --console=plain --no-daemon --stacktrace 

- # APK -> app/build/outputs/apk/debug/the-livre-magicae.apk 

Or use the helper scripts (unchanged from Patch 21): 

- ./scripts/build.sh          # debug APK  -> app/build/outputs/apk/debug/the-livre-mag ./scripts/check_syntax.sh   # fast Kotlin compile gate (no APK) 

- ./scripts/validate.sh       # clean + compile + unit tests + APK + permission check ./scripts/release.sh        # release APK -> release/the-livre-magicae-release.apk 

## CI (GitHub Actions) 

- <mark>android-ci.yml</mark> — push/PR. JDK 17 + Gradle, builds debug APK, uploads it, asserts no INTERNET permission. Does not run unit tests (stays green). 

- <mark>release.yml</mark> — tag-triggered (e.g. <mark>v1.22</mark> ). Builds release APK, runs 

- <mark>testDebugUnitTest,</mark> creates a GitHub Release with the APK. (See Section 8 re: the sample-EPUB test caveat.) 

## 7. APK inspection (what was verified) 

The built APK was checked with <mark>aapt2:</mark> 

package: name='com.epubreader.app' versionCode='23' versionName='1.22' application-label:'The Livre Magicae' 

### Permissions contain only the standard 

<mark>com.epubreader.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION</mark> — no 

<mark>android.permission.INTERNET</mark> , preserving the app's fully-offline, 

privacy-first guarantee. 

## 8. Known items to be aware of 

- Unit tests need a real sample EPUB (and the release workflow runs them). <mark>EpubParserTest</mark> reads from the hardcoded path 

- <mark>/home/user/workspace/sample.epub</mark> . The bundled test resource 

- <mark>app/src/test/resources/epub/Pride and Prejudice.epub</mark> is a truncated stub and 

- will fail with <mark>ZipException. android-ci.yml</mark> does not run unit tests, so 

- pushes/PRs stay green; <mark>release.yml</mark> does run <mark>testDebugUnitTest,</mark> so a 

- tag-triggered release fails until a valid <mark>sample.epub</mark> exists at that path or the test path is adjusted. Pre-existing; unaffected by Patch 22. 

- APK output filename. <mark>app/build.gradle.kts</mark> renames every variant output 

- to <mark>the-livre-magicae.apk.</mark> CI upload, <mark>validate.sh, build.sh</mark> , 

- <mark>release.yml, release.sh,</mark> and <mark>README.md</mark> all expect that exact name. Keep them in sync if you ever rename it. 

- Unused layouts. <mark>bottom_sheet_list.xml, bottom_sheet_search.xml</mark> , 

- <mark>dialog_search.xml, dialog_sort.xml, dialog_text_input.xml,</mark> 

- <mark>dialog_book_details.xml</mark> , <mark>item_sort_option.xml,</mark> and 

- <mark>item_section_header.xml</mark> are not currently inflated by any 

- adapter/activity. They are le� in place for potential reuse. 

## 9. Patch 22 file map (quick reference) 

Changed source files (vs Patch 21): 

app/build.gradle.kts                                         # versionCode 23, versio app/src/main/res/layout/activity_reader.xml                  # new @id/tvPageInfo lab app/src/main/java/com/epubreader/app/ReaderActivity.kt       # setPageText updates tv 

Build output: <mark>app/build/outputs/apk/debug/the-livre-magicae.apk</mark> 

