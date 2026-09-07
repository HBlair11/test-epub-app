# — The Livre Magicae Patch 14 Release Notes 

Version: 1.14-patch14 (versionCode 14) 

" " Package: <mark>com.epubreader.app</mark> (display label The Livre Magicae ) Permissions: only the auto-generated <mark>DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION.</mark> No <mark>INTERNET.</mark> 

Patch 14 follows up on Patch 13's two reader fixes: the dark-mode white-bar fix is extended to sepia, and the embedded-font fix gains an 

<mark>Access-Control-Allow-Origin</mark> header to address <mark>@font-face</mark> fonts being 

' blocked by the WebView s CORS handling. 

## Issue #1 — white bars over headings: now also fixed in Sepia 

Symptom: Patch 13 fixed the dark theme's white bars over chapter headings, but the sepia theme still showed them. 

Cause: the background-neutralizing override was only applied in dark mode <mark>(darkTextOverride</mark> returned <mark>""</mark> for non-dark). A heading with a light/white <mark>background-color</mark> blends into the white page in light mode (fine), but in sepia the white box stands out against the sepia page. 

Fix: apply the override in dark and sepia. <mark>body *</mark> keeps its page background (set in <mark>buildReaderCss</mark> ); descendants get 

<mark>background-color: transparent !important;</mark> and the ink color is pushed through every element. Light mode stays untouched. 

File: <mark>ReaderActivity.kt</mark> → <mark>darkTextOverride()</mark> . 

## Issue #2 — embedded <mark>@font-face</mark> fonts still not loading 

Status: the Patch 13 encoding fix made images load (the heart now appears, though it can render mis-sized — see below), but the fonts still do not. This update adds the most likely remaining cause as a fix, and keeps the diagnostic logging so it can be confirmed on-device. 

## Why images now work but fonts don't 

<mark><img></mark> and <mark>@font-face</mark> fonts are treated differently by the WebView: 

- <mark><img></mark> is not CORS-restricted — it loads cross-origin without any special headers. A�er the encoding fix, images load. 

- <mark>@font-face</mark> fonts are subject to CORS. When the chapter is loaded via <mark>loadDataWithBaseURL,</mark> the document's origin can be treated as opaque, so the 

WebView may treat font fetches to the <mark>https://epub.local</mark> virtual host as cross-origin and block them unless the response carries 

<mark>Access-Control-Allow-Origin</mark> . 

That asymmetry is exactly why images started working a�er the encoding fix but fonts did not. 

## Fix 

Send <mark>Access-Control-Allow-Origin: *</mark> on every intercepted resource response (using the 5-argument <mark>WebResourceResponse</mark> constructor). This is harmless for same-origin requests and unblocks cross-origin font loading: 

val headers = mapOf("Access-Control-Allow-Origin" to "*") return WebResourceResponse(mime, charset, 200, "OK", headers, ByteArrayInputStream(by 

Combined with the Patch 13 encoding fix (null charset for binary), this should now let the "Emmascript MVB Std" chapter font, the Avenir body fonts, and the heart image all load and render as they do in Calibre / Readium. 

File: <mark>EpubResourceResolver.kt</mark> → <mark>intercept().</mark> 

## If the fonts still do not load 

The diagnostic log lines tagged <mark>[PATCH13-RES]</mark> are still present. Capture logcat filtered to the resolver while opening the chapter: 

- adb logcat s EpubResourceResolver 

### Look for: 

- <mark>[PATCH13-RES] OK entry=page_styles.css …</mark> — confirms the @font-face stylesheet is being loaded. 

- <mark>[PATCH13-RES] OK entry=fonts/00001.otf mime=font/otf charset=null bytes=…</mark> 

- confirms the font files are being requested and served. 

- <mark>[PATCH13-RES] NOT FOUND …</mark> or no font line at all — the WebView is not requesting the fonts (a deeper <mark>loadDataWithBaseURL</mark> + <mark>@font-face</mark> issue); in that case the fix is to inline the fonts as base64 data URIs in the CSS, 

- which removes the need for a separate font request entirely. Send me those log lines and I will implement that. 

## Heart image mis-sized / overflow 

You also noted the heart sometimes overflows instead of staying small. The book styles the heart with <mark>.height_1em { width: 10px; height: auto; … }</mark> (defined in <mark>stylesheet.css</mark> ). Once the stylesheets load fully (with this patch's fixes), that 10px constraint should apply. If a heart still overflows, it is likely a case where <mark>.height_1em</mark> isn't reaching the element — the same logcat (does the stylesheet load? is the image served at its real size?) will pinpoint it. 

## How to verify on device 

1. Install <mark>The-Livre-Magicae-Patch-14.apk</mark> over your existing install 

   - (library/settings preserved — same package, versionCode bumped). 

2. Open The Love Hypothesis, Chapter One: 

      - "Chapter One" in the Emmascript script font. 

      - The heart + gray box appear before "HYPOTHESIS". 

      - Body in Avenir. 

3. Switch to Sepia (and Dark): chapter headings are visible, no white bars. 

4. (Optional) <mark>adb logcat -s EpubResourceResolver</mark> to see <mark>[PATCH13-RES]</mark> 

   - lines confirming fonts/stylesheet resolution. 

## Build & deliverables 

- APK: <mark>The-Livre-Magicae-Patch-14.apk</mark> (~6.9MB, versionCode 14) 

- Source: <mark>The-Livre-Magicae-Patch-14-source.zip</mark> (201 files) 

- Build: JDK 17 / Android SDK 35, <mark>./gradlew assembleDebug,</mark> verified via 

- <mark>aapt2 dump badging</mark> (label <mark>The Livre Magicae,</mark> versionCode 14, no INTERNET). 

## Files changed in Patch 14 

|File|Change|
|---|---|
|app/src/main/java/com/epubreader/app/ReaderActivity.kt|darkTextOverride():<br>apply background-<br>neutralizing override in dark<br>and sepia (Issue#1,sepia).|
|app/src/main/java/com/epubreader/app/epub/EpubResourceResolver.kt|intercept():send<br>Access-Control-<br>Allow-Origin: * via5-<br>arg<br>WebResourceResponse<br>(Issue#2,fonts).|



|File<br>app/build.gradle.kts|Change<br>versionCode13 → 14,<br>versionName<br>1.13-<br>patch13 →<br>1.14-<br>patch14.|
|---|---|
