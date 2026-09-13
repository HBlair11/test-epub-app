package com.epubreader.app

import com.epubreader.app.util.SystemBarController

import android.annotation.SuppressLint
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ActionMode
import android.view.MenuItem
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.math.roundToInt
import android.widget.SeekBar
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.asLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.data.AppDatabase
import com.epubreader.app.data.BookEntity
import com.epubreader.app.data.BookmarkEntity
import com.epubreader.app.data.DictionaryHistoryEntity
import com.epubreader.app.data.PrefsManager
import com.epubreader.app.data.TtsSettingsEntity
import com.epubreader.app.epub.ReaderSelectionBridge
import com.epubreader.app.epub.ReaderSelectionLocator
import com.epubreader.app.databinding.ActivityReaderBinding
import com.epubreader.app.epub.EpubBook
import com.epubreader.app.epub.EpubParser
import com.epubreader.app.epub.EpubResourceResolver
import com.epubreader.app.epub.EpubSearchEngine
import com.epubreader.app.epub.ReaderPageMapping
import com.epubreader.app.epub.ReaderTtsController
import com.epubreader.app.tts.ReaderTtsService
import com.epubreader.app.ui.BookmarkAdapter
import com.epubreader.app.ui.HighlightListAdapter
import com.epubreader.app.ui.ReaderSettingsActivity
import com.epubreader.app.ui.ReaderTheme
import com.epubreader.app.ui.SearchResultAdapter
import com.epubreader.app.ui.TocAdapter
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderActivity : AppCompatActivity() {


    private lateinit var binding: ActivityReaderBinding
    private lateinit var prefs: PrefsManager
    private var pendingSelectionCallback: ((ReaderSelectionLocator?) -> Unit)? = null
    private var definitionPopup: PopupWindow? = null
    /** Active text-selection ActionMode (the floating Copy/Translate/… toolbar).
     *  Held so it can be dismissed when the user navigates away or taps the
     *  page. Patch v37. */
    private var currentSelectionActionMode: ActionMode? = null
    private var selectionToolbarPopup: PopupWindow? = null
    private var currentReaderSelection: ReaderSelectionLocator? = null
    /** Time-based guard: a tap that should NOT turn the page or toggle chrome
     *  (e.g. it landed on a highlight, or dismissed a text selection) sets this
     *  so the GestureDetector's onSingleTapConfirmed is ignored. Patch v37. */
    private var suppressReaderTapUntilMs = 0L
    /** Tracks an in-progress tap that is dismissing an active text selection.
     *  The entire gesture (DOWN → MOVE → UP) is consumed so it never reaches the
     *  GestureDetector. Patch v37. */
    private var consumingSelectionDismissTap = false
    private var dictionaryLookup: com.epubreader.app.epub.DictionaryLookup? = null
    private var ttsController: ReaderTtsController? = null
    private var readingSessionStartedAt: Long? = null
    private var readingSessionLastInteractionAt: Long = 0L

    /** Patch v37: POST_NOTIFICATIONS request for the read-aloud media notification. */
    private val notificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                updateTtsServiceState()
            }
        }

    /** Debounced writer for per-book TTS settings. */
    private val saveTtsSettingsRunnable = Runnable { saveTtsSettingsNow() }
    private var readingSessionActiveSeconds: Int = 0
    private var readingSessionStartSpine: Int = 0
    private var readingSessionStartPage: Int = 0

    // Patch 11 "Screen On" controller — keeps the screen awake for 10 minutes
    // beyond the system timeout while the reader is in the foreground.
    private lateinit var keepScreenOnController: com.epubreader.app.util.KeepScreenOnController
    private lateinit var db: AppDatabase

    private var bookId: Long = -1L
    private var bookEntity: BookEntity? = null
    private var epub: EpubBook? = null
    private var resolver: EpubResourceResolver? = null
    private var spineIndex: Int = 0
    private var currentScrollRatio: Float = 0f
    private var currentPageInChapter: Int = 0
    private var pagesInChapter: Int = 1
    private var pendingFragment: String? = null
    private var chromeVisible: Boolean = false
    private var ttsOverlayRestoresChrome: Boolean = false
    private var restoreRatio: Float? = null
    /** Exact in-chapter page to restore after a cross-spine page seek. This uses
     *  the same Caesura page index that the visible reader already uses. */
    private var pendingTargetPageInChapter: Int? = null

    /** spineIndex -> section label, derived from the embedded nav TOC (not toc.xhtml). */
    private var tocSectionMap: Map<Int, String> = emptyMap()

    /** ordered (spineIndex,label) sections for nearest-previous fallback. */
    private var tocSections: List<Pair<Int, String>> = emptyList()

    /** Per-chapter page counts measured by the measurement WebView (-1 = not measured yet). */
    private var chapterPageCounts: IntArray? = null

    /** Once every chapter's page count is measured, the bottom timeline switches
     *  from chapter-level (max = spine.size-1) to per-page (max = total pages-1),
     *  so dragging the seeker lands on the exact page instead of the chapter's
     *  first page. */
    private var perPageSeekerActive: Boolean = false

    private var userSeeking = false
    private var pendingSeekProgress: Int? = null
    private var progressRequestToken = 0

    /** Temporary reader navigation history. Only explicit navigation events are recorded;
     * normal page turns and layout restores are intentionally not. */
    private data class ReaderLocation(
        val spineIndex: Int,
        val pageInChapter: Int,
        val ratio: Float,
    )

    private val backHistory = ArrayDeque<ReaderLocation>()
    private val forwardHistory = ArrayDeque<ReaderLocation>()
    private var restoringHistoryLocation = false
    /** Location associated with the current history cursor. Normal page turns never update this. */
    private var historyCursorLocation: ReaderLocation? = null
    /** Set only by explicit navigation that will resolve its final page after a chapter load. */
    private var pendingHistoryCursorAfterRestore = false

    /** Temporary semantic anchor used only while reader settings reflow the current chapter. */
    private data class ReflowAnchor(
        val text: String,
        val fallbackPage: Int,
    )

    private var pendingReflowAnchor: ReflowAnchor? = null
    private var manualSeekTouch = false
    private var manualSeekFinished = false
    /** Exact page requested by the user. A stale WebView poll must not overwrite this
     *  location while the visible WebView is applying gotoPage(). */
    private var pendingExactSeekLocation: ReaderLocation? = null
    private var exactSeekUiLocation: ReaderLocation? = null

    private var pendingProgressValue = 0f
    private var pendingProgressSpine = 0
    private var pendingProgressRatio = 0f
    private var lastPersistedProgress: Float? = null
    private var lastPersistedSpine = -1
    private var lastPersistedRatio = 0f
    private var lastProgressPersistAt = 0L

    private val persistProgressRunnable = Runnable {
        persistProgressNow()
    }

    @Volatile
    private var measuring: Boolean = false

    private var measuringIndex: Int = -1
    private var measuringLoaded: Int = -1

    @Volatile
    private var measureCancelled: Boolean = false

    private var measureGeneration: Int = 0
    private var activeMeasureGeneration: Int = 0
    private var expectedMeasureUrl: String? = null

    private var readerGeneration: Int = 0
    private var activeReaderGeneration: Int = 0

    private val restartMeasurementRunnable = Runnable {
        startMeasurement()
    }
    private val handler = Handler(Looper.getMainLooper())
    private val progressPoller = object : Runnable {
        override fun run() {
            pollProgress(); handler.postDelayed(this, 1500)
        }
    }
    private val measureWatchdog = object : Runnable {
        override fun run() {
            if (!measuring || measureCancelled) return

            val index = measuringIndex
            val book = epub ?: return

            if (index !in book.spine.indices) {
                measuring = false
                expectedMeasureUrl = null
                updatePageIndicator()
                return
            }

            if (measuringLoaded != index) return

            chapterPageCounts?.let { counts ->
                if (index in counts.indices) {
                    counts[index] = 1
                }
            }

            measuringIndex = index + 1

            if (measuringIndex >= book.spine.size) {
                measuring = false
                expectedMeasureUrl = null
                updatePageIndicator()
            } else {
                loadForMeasurement(measuringIndex)
            }
        }
    }

    private val alphaFallback = object : Runnable {
        override fun run() {
            if (binding.webView.alpha == 0f) binding.webView.alpha = 1f
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = PrefsManager(applicationContext)
        keepScreenOnController = com.epubreader.app.util.KeepScreenOnController(this, prefs)
        db = AppDatabase.get(applicationContext)
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarController.apply(this)
        applyWindowTheme()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        if (bookId < 0) {
            finish(); return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            db.bookDao().markOpened(bookId, System.currentTimeMillis())
        }

        setupWebView()
        setupMeasureWebView()
        ttsController = ReaderTtsController(applicationContext, { playing ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateTtsControlsUi(playing)
                updateTtsServiceState()
            }
        }, {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val next = epub?.let { it.spine.getOrNull(spineIndex + 1) }
                if (next != null) {
                    goToSpine(spineIndex + 1)
                    handler.postDelayed({ startTtsForCurrentChapter() }, 450)
                } else {
                    binding.tvTtsStatus.text = getString(R.string.action_read_aloud)
                }
            }
        }, { segment, start, end ->
            // Word-level range callback. The controller reports offsets against
            // the full structural segment even when it is resuming a suffix.
            runOnUiThread { highlightSpokenWord(segment, start, end) }
        }, { remainingMs ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.tvTtsStatus.text = getString(R.string.tts_sleep_remaining, (remainingMs / 60000L).toInt() + 1)
            }
        }, {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Snackbar.make(binding.root, R.string.tts_sleep_finished, Snackbar.LENGTH_SHORT).show()
            }
        }, { segment ->
            // Sentence-level highlight is anchored to the structural TTS
            // segment, not found by searching the whole chapter for a string.
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                highlightSpokenWord(segment, 0, segment.text.length)
            }
        })
        ReaderTtsService.attach(ttsController)
        setupChrome()
        setupOverlays()
        loadBook()
    }

    // ---------------------------------------------------------------- theme
    private fun applyWindowTheme() {
        val (bg, _) = readerColors()
        // The status bar and navigation bar are always solid black (per the
        // user's request), regardless of the reading theme. The window decor
        // and the root container are painted black so the area BEHIND the
        // system bars is black too; only the WebView (the book page) and the
        // static chrome bars use the reading/chrome colors.
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.setBackgroundColor(Color.BLACK)
        binding.root.setBackgroundColor(Color.BLACK)
        binding.webView.setBackgroundColor(bg)
        binding.measureWebView.setBackgroundColor(bg)
        binding.topBar.setBackgroundColor(readerSurface())
        binding.bottomBar.setBackgroundColor(readerSurface())
        binding.tocBookmarkOverlay.setBackgroundColor(readerSurface())
        binding.searchOverlay.setBackgroundColor(readerSurface())
        // Page indicator: no background pill — ink-colored text (contrasts with
        // the page) lightened by the view alpha so it blends like Kindle/ReadEra.
        binding.tvPageIndicator.setTextColor(inkColor())
    }

    /** Content-area colors (the EPUB page itself). Only this changes with the
     *  reading theme. Patch 17 (Addition #1): colors now come from the
     *  single-source-of-truth [ReaderTheme] registry (ReaderThemes.kt) —
     *  edit a theme's bg/ink there and it propagates here automatically. */
    private fun readerColors(): Pair<Int, String> {
        val t = ReaderTheme.byId(prefs.theme)
        return t.bgColor to t.inkHex
    }

    /** Reader chrome background — STATIC (eggplant) for every reading theme and
     *  for app day/night. Read from the color resource so palette changes in
     *  colors.xml propagate here (single source of truth). */
    private fun readerSurface(): Int = getColor(R.color.reader_chrome_bg)

    private fun inkColor(): Int = Color.parseColor(readerColors().second)

    private fun bottomGuardPx(): Int = if (prefs.pageBottomMargin) 56 else 0

    /** Patch 12: top reading margin — the vertical breathing room reserved ABOVE
     *  the page content so the first line of a chapter never sits flush against the
     *  top status bar. It mirrors the bottom guard so the two spaces are equal and
     *  symmetric; both are controlled by the single "Top & bottom margin" reader
     *  setting so they stay in sync. The reserved space is painted with the reading
     *  background color (white / sepia / black), NOT reader content. */
    private fun topGuardPx(): Int = if (prefs.pageBottomMargin) 56 else 0

    // ---------------------------------------------------------------- webview
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
                resolver?.intercept(request)

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.contains(EpubResourceResolver.VIRTUAL_HOST)) {
                    navigateToUrl(url); return true
                }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (_: Exception) {
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                val generation = activeReaderGeneration

                binding.webView.evaluateJavascript(
                    "if(window.Caesura){window.Caesura.apply();}"
                ) {
                    if (generation != activeReaderGeneration) {
                        return@evaluateJavascript
                    }

                    handler.removeCallbacks(progressPoller)
                    handler.post(progressPoller)

                    handler.removeCallbacks(alphaFallback)

                    handler.postDelayed({
                        if (generation == activeReaderGeneration) {
                            applyPendingFragmentOrRestore()
                            // Inject existing highlights after the chapter content
                            // is loaded and Caesura pagination is applied.
                            injectHighlightsForChapter()
                        }
                    }, 140L)

                    handler.postDelayed(alphaFallback, 1500L)
                }
            }
        }
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.addJavascriptInterface(
            ReaderSelectionBridge { selection ->
                runOnUiThread {
                    val callback = pendingSelectionCallback
                    pendingSelectionCallback = null
                    callback?.invoke(selection)
                }
            },
            "LivreSelection"
        )
        // Highlight tap bridge: when a <mark> element is tapped in the WebView,
        // it calls LivreHighlight.onHighlightTap(id) to open the note sheet.
        binding.webView.addJavascriptInterface(
            HighlightBridge(),
            "LivreHighlight"
        )
        binding.webView.setOnLongClickListener { false }

        // Keep Android/Chromium text selection intact. The Activity-level
        // ActionMode lifecycle below adds Define and Highlight after WebView
        // finishes its own menu preparation, without overriding WebView internals.
    }

    private fun captureCurrentSelection(onCaptured: ((ReaderSelectionLocator?) -> Unit)? = null) {
        pendingSelectionCallback = onCaptured
        val href = epub?.spine?.getOrNull(spineIndex)?.href.orEmpty()
        if (href.isBlank()) return
        val escapedHref = org.json.JSONObject.quote(href)
        // Patch v37: also reports the selection's bounding rect (WebView-local
        // CSS pixels) so the definition card can anchor to it instead of a
        // fixed-position bottom sheet.
        binding.webView.evaluateJavascript(
            "(function(){var s=window.getSelection&&window.getSelection();if(!s||s.rangeCount===0||!s.toString().trim())return;var r=s.getRangeAt(0);var rect=r.getBoundingClientRect();function p(n){if(n&&n.nodeType!==1)n=n.parentNode;var a=[];while(n&&n.nodeType===1){var i=0,q=n.previousSibling;while(q){if(q.nodeType===n.nodeType&&q.nodeName===n.nodeName)i++;q=q.previousSibling;}a.unshift(n.nodeName.toLowerCase()+':'+i);n=n.parentNode;}return a.join('/');}var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);var allText='',nodes=[];while(walker.nextNode()){nodes.push({node:walker.currentNode,start:allText.length});allText+=walker.currentNode.textContent;}var t=s.toString().trim();var selStart=0,selEnd=0;var sc=r.startContainer,ec=r.endContainer;for(var i=0;i<nodes.length;i++){if(nodes[i].node===sc)selStart=nodes[i].start+r.startOffset;if(nodes[i].node===ec){selEnd=nodes[i].start+r.endOffset;break;}}var prefix=allText.slice(Math.max(0,selStart-40),selStart);var suffix=allText.slice(selEnd,selEnd+40);LivreSelection.onSelectionPayload(t," + escapedHref + ",p(r.startContainer),r.startOffset,p(r.endContainer),r.endOffset,prefix,suffix,Math.round(rect.left),Math.round(rect.top),Math.round(rect.right),Math.round(rect.bottom));})();",
            null
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupMeasureWebView() {
        binding.measureWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            allowFileAccess = false
            allowContentAccess = false
        }
        binding.measureWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
                resolver?.intercept(request)

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                val webView = view ?: return

                if (measureCancelled || !measuring) return

                val generation = activeMeasureGeneration
                val index = measuringIndex

                if (!isExpectedMeasurementUrl(url, generation, index)) return

                webView.evaluateJavascript(
                    "if(window.Caesura){window.Caesura.apply();}"
                ) {
                    if (measureCancelled || !measuring) {
                        return@evaluateJavascript
                    }

                    if (generation != activeMeasureGeneration) {
                        return@evaluateJavascript
                    }

                    if (index != measuringIndex) {
                        return@evaluateJavascript
                    }

                    if (!isExpectedMeasurementUrl(url, generation, index)) {
                        return@evaluateJavascript
                    }

                    handler.postDelayed(
                        { readMeasuredCount(generation, index, url ?: return@postDelayed) },
                        120L
                    )
                }
            }
        }
    }

    private fun readMeasuredCount(
        generation: Int,
        index: Int,
        loadedUrl: String,
    ) {
        if (measureCancelled || !measuring) return
        if (generation != activeMeasureGeneration) return
        if (index != measuringIndex) return
        if (!isExpectedMeasurementUrl(loadedUrl, generation, index)) return

        binding.measureWebView.evaluateJavascript(
            "(function(){return window.Caesura ? window.Caesura.pageCount() : 1;})();"
        ) { result ->
            if (measureCancelled || !measuring) {
                return@evaluateJavascript
            }

            if (generation != activeMeasureGeneration) {
                return@evaluateJavascript
            }

            if (index != measuringIndex) {
                return@evaluateJavascript
            }

            if (!isExpectedMeasurementUrl(loadedUrl, generation, index)) {
                return@evaluateJavascript
            }

            handler.removeCallbacks(measureWatchdog)

            val count = result
                ?.trim('"')
                ?.toIntOrNull()
                ?.coerceAtLeast(1)
                ?: 1

            chapterPageCounts?.let { counts ->
                if (index in counts.indices) {
                    counts[index] = count
                }
            }

            measuringIndex = index + 1

            val book = epub
            if (book == null || measuringIndex >= book.spine.size) {
                measuring = false
                expectedMeasureUrl = null
                updatePageIndicator()
                enablePerPageSeeker()
                updateHistoryUi()
                persistScreenPageCounts()
            } else {
                loadForMeasurement(measuringIndex)
            }
        }
    }

    private fun isExpectedMeasurementUrl(
        url: String?,
        generation: Int,
        index: Int,
    ): Boolean {
        if (url.isNullOrBlank()) return false

        return url.contains("measure=$generation") &&
                url.contains("idx=$index")
    }

    private fun loadForMeasurement(index: Int) {
        val book = epub ?: return

        if (measureCancelled || !measuring) return

        if (index !in book.spine.indices) {
            measuring = false
            expectedMeasureUrl = null
            return
        }

        measuringLoaded = index

        val item = book.spine[index]
        val html = buildChapterHtml(item.href) ?: run {
            chapterPageCounts?.set(index, 1)
            measuringIndex = index + 1

            if (measuringIndex >= book.spine.size) {
                measuring = false
                expectedMeasureUrl = null
                updatePageIndicator()
                enablePerPageSeeker()
                updateHistoryUi()
                persistScreenPageCounts()
            } else {
                loadForMeasurement(measuringIndex)
            }
            return
        }

        val baseUrl = EpubResourceResolver.baseUrl(bookId, item.href)
        val separator = if (baseUrl.contains("?")) "&" else "?"
        val measurementUrl =
            "$baseUrl${separator}measure=$activeMeasureGeneration&idx=$index"

        expectedMeasureUrl = measurementUrl

        handler.removeCallbacks(measureWatchdog)

        binding.measureWebView.loadDataWithBaseURL(
            measurementUrl,
            html,
            "text/html",
            "UTF-8",
            null
        )

        handler.postDelayed(measureWatchdog, 4000L)
    }

    /** Stable fingerprint for the exact reader layout used by screen-page counts.
     *  Width/height cover orientation/window changes; reader settings cover every
     *  value that can alter pagination. The EPUB checksum is already the book-level
     *  identity in Room, so it does not need to be duplicated here. */
    private fun screenPageLayoutKey(): String? {
        val width = binding.webView.width
        val height = binding.webView.height
        if (width <= 0 || height <= 0) return null
        return listOf(
            width,
            height,
            resources.displayMetrics.density,
            prefs.font,
            prefs.fontSize,
            prefs.lineHeight,
            prefs.margin,
            prefs.align,
            prefs.hyphenation,
            prefs.pageBottomMargin,
            topGuardPx(),
            bottomGuardPx(),
        ).joinToString("|")
    }

    private fun parseScreenPageMap(csv: String?, expectedSize: Int): IntArray? {
        if (csv.isNullOrBlank()) return null
        val values = csv.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (values.size != expectedSize || values.any { it < 1 }) return null
        return values.toIntArray()
    }

    private fun loadCachedScreenPageCounts(entity: BookEntity): Boolean {
        val key = screenPageLayoutKey() ?: return false
        if (entity.screenPageLayoutKey != key) return false
        val cached = parseScreenPageMap(entity.screenPageMapCsv, epub?.spine?.size ?: 0) ?: return false
        chapterPageCounts = cached
        perPageSeekerActive = cached.size > 1 && ReaderPageMapping.totalPages(cached) > 1
        if (perPageSeekerActive) {
            binding.seekChapter.max = ReaderPageMapping.totalPages(cached) - 1
            syncSeekBarFromCurrentPage()
            updatePageIndicator()
            updateSectionPages()
            updateHistoryUi()
        }
        return true
    }

    private fun persistScreenPageCounts() {
        if (bookId < 0L) return
        val key = screenPageLayoutKey() ?: return
        val counts = chapterPageCounts ?: return
        if (counts.isEmpty() || counts.any { it < 1 }) return
        val csv = counts.joinToString(",")
        lifecycleScope.launch(Dispatchers.IO) {
            db.bookDao().updateScreenPageMap(bookId, csv, key)
        }
    }

    /** Kick off background measurement of every chapter's page count. */
    private fun startMeasurement() {
        val book = epub ?: return
        if (book.spine.isEmpty()) return

        measureGeneration += 1
        activeMeasureGeneration = measureGeneration

        handler.removeCallbacks(measureWatchdog)

        if (chapterPageCounts == null || chapterPageCounts?.size != book.spine.size) {
            chapterPageCounts = IntArray(book.spine.size) { -1 }
        }

        measureCancelled = false
        measuring = true
        measuringIndex = 0
        measuringLoaded = -1
        expectedMeasureUrl = null

        binding.measureWebView.post {
            loadForMeasurement(0)
        }
    }

    private fun cancelMeasurement() {
        measureGeneration += 1
        activeMeasureGeneration = measureGeneration

        measureCancelled = true
        measuring = false
        measuringIndex = -1
        measuringLoaded = -1
        expectedMeasureUrl = null

        handler.removeCallbacks(measureWatchdog)
        handler.removeCallbacks(restartMeasurementRunnable)

        binding.measureWebView.stopLoading()
    }

    private fun startTtsForCurrentChapter() {
        val book = epub ?: return
        val item = book.spine.getOrNull(spineIndex) ?: return
        if (ttsController?.state == ReaderTtsController.State.UNAVAILABLE) {
            Snackbar.make(binding.root, R.string.tts_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        updateTtsServiceState()
        // Resolve the first readable text position in the page that is actually
        // visible. The JavaScript walker mirrors ReaderTtsDocumentBuilder:
        // ignored elements are skipped and <br> contributes one source space.
        // This gives the controller the same raw character coordinate instead of
        // using a guessed screen point or falling back to chapter offset zero.
        binding.webView.evaluateJavascript(
            """(function(){
                try{
                    var body=document.body;if(!body)return 0;
                    var ignored={HEAD:1,SCRIPT:1,STYLE:1,NOSCRIPT:1,SVG:1,MATH:1};
                    var walker=document.createTreeWalker(body,NodeFilter.SHOW_TEXT,null,false);
                    var raw=0,best=-1,bestTop=1e9;
                    function inIgnored(n){var p=n.parentElement;while(p){if(ignored[p.tagName])return true;p=p.parentElement;}return false;}
                    function visibleOffset(n){
                        var len=n.textContent?n.textContent.length:0;
                        for(var i=0;i<len;i++){
                            var r=document.createRange();r.setStart(n,i);r.setEnd(n,Math.min(i+1,len));
                            var rect=r.getBoundingClientRect();
                            if(rect.width>0&&rect.height>0&&rect.right>0&&rect.left<window.innerWidth&&rect.bottom>0&&rect.top<window.innerHeight){
                                var y=rect.top;
                                if(y<bestTop){bestTop=y;best=raw+i;}
                                break;
                            }
                        }
                        raw+=len;
                    }
                    while(walker.nextNode()){
                        var n=walker.currentNode;
                        if(inIgnored(n))continue;
                        visibleOffset(n);
                    }
                    // Recompute raw offsets while treating <br> exactly as the
                    // builder does, then return the earliest visible source char.
                    raw=0;best=-1;bestTop=1e9;
                    var w=document.createTreeWalker(body,NodeFilter.SHOW_ALL,null,false),n;
                    while(n=w.nextNode()){
                        if(n.nodeType===1){
                            if(ignored[n.tagName]){try{w.currentNode=n;w.nextNode();}catch(e){} }
                            if(n.tagName==='BR')raw++;
                            continue;
                        }
                        if(n.nodeType!==3||inIgnored(n))continue;
                        var len=n.textContent?n.textContent.length:0;
                        for(var i=0;i<len;i++){
                            var r=document.createRange();r.setStart(n,i);r.setEnd(n,Math.min(i+1,len));
                            var rect=r.getBoundingClientRect();
                            if(rect.width>0&&rect.height>0&&rect.right>0&&rect.left<window.innerWidth&&rect.bottom>0&&rect.top<window.innerHeight){
                                if(rect.top<bestTop){bestTop=rect.top;best=raw+i;}
                                break;
                            }
                        }
                        raw+=len;
                    }
                    return Math.max(0,best);
                }catch(e){return 0;}
            })();""",
        ) { result ->
            val offset = result?.trim()?.removeSurrounding("\"")?.toIntOrNull() ?: 0
            lifecycleScope.launch { ttsController?.speakChapter(book.file, item.href, offset) }
        }
    }

    // ------------------------------------------------------------- patch v37 tts

    /** SeekBar progress -> engine speech rate (0.5 + N * 0.05). */
    private fun ttsRateFor(progress: Int): Float = 0.5f + progress * 0.05f

    /** SeekBar progress -> engine pitch (0.5 + N * 0.05). */
    private fun ttsPitchFor(progress: Int): Float = 0.5f + progress * 0.05f

    /** Transport + status state for the dedicated TTS overlay. The overlay's
     *  own visibility is managed by [showTtsOverlay] / [hideTtsOverlay] (opened
     *  by the speaker icon, closed by the back / stop controls); this only
     *  refreshes the play/pause icon and the status line. */
    private fun updateTtsControlsUi(playing: Boolean) {
        binding.btnTtsPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnTtsPlayPause.contentDescription = getString(if (playing) R.string.tts_pause else R.string.tts_play)
        binding.tvTtsStatus.text = when (ttsController?.state) {
            ReaderTtsController.State.PLAYING -> getString(R.string.tts_status_reading)
            ReaderTtsController.State.PAUSED -> getString(R.string.tts_status_paused)
            else -> getString(R.string.tts_status_reading)
        }
    }

    /** Shows the Read Aloud panel as the only reader chrome. The normal reader
     *  top bar, bottom timeline, history controls and page indicator are hidden
     *  while TTS is open; the EPUB page itself remains visible underneath. */
    private fun showTtsOverlay() {
        clearReaderSelection()
        ttsOverlayRestoresChrome = chromeVisible
        chromeVisible = false
        binding.topBar.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.tvPageIndicator.visibility = View.GONE
        binding.ttsOverlay.visibility = View.VISIBLE
        binding.tvTtsBookTitle.text = epub?.metadata?.title?.ifBlank { null }
            ?: bookEntity?.title
            ?: getString(R.string.app_name)
        binding.tvTtsSection.text = sectionLabel()
        val playing = ttsController?.state == ReaderTtsController.State.PLAYING
        updateTtsControlsUi(playing)
        updateHistoryUi()
    }

    /** Hides the Read Aloud panel and restores the reader chrome to the exact
     *  visibility state it had when TTS was opened. Read-aloud itself is not
     *  stopped here. */
    private fun hideTtsOverlay() {
        binding.ttsOverlay.visibility = View.GONE
        chromeVisible = ttsOverlayRestoresChrome
        binding.topBar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        binding.tvPageIndicator.visibility =
            if (!chromeVisible && !overlayVisible()) View.VISIBLE else View.GONE
        updateHistoryUi()
        updatePageIndicator()
    }

    /** Starts/stops the keep-alive foreground service with the media
     *  notification when background playback is enabled. The service stays
     *  alive while paused so the notification can offer Resume. */
    private fun updateTtsServiceState() {
        val state = ttsController?.state ?: ReaderTtsController.State.INITIALIZING
        val active = state == ReaderTtsController.State.PLAYING || state == ReaderTtsController.State.PAUSED
        if (active && prefs.ttsBackgroundPlayback) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            val title = epub?.metadata?.title ?: getString(R.string.app_name)
            ReaderTtsService.start(this, bookId, title)
        } else {
            ReaderTtsService.stop(this)
        }
    }

    private fun stopTtsCompletely() {
        ttsController?.stop()
        clearSpokenWordHighlight()
        ReaderTtsService.stop(this)
        binding.tvTtsStatus.text = getString(R.string.action_read_aloud)
        hideTtsOverlay()
    }

    private fun showSleepTimerMenu() {
        val minutes = intArrayOf(5, 10, 15, 30, 45, 60)
        val labels = minutes.map { getString(R.string.tts_sleep_minutes, it) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.tts_sleep_timer)
            .setSingleChoiceItems(labels, -1) { dialog, which ->
                ttsController?.startSleepTimer(minutes[which])
                dialog.dismiss()
            }
            .setNeutralButton(R.string.tts_sleep_off) { _, _ ->
                ttsController?.cancelSleepTimer()
                updateTtsControlsUi(ttsController?.state == ReaderTtsController.State.PLAYING)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Read-aloud settings sub-screen (opened from the TTS overlay's tune
     *  button). Hosts the speed/pitch sliders, sleep timer, background-playback
     *  toggle and voice picker so the main overlay stays clean. */
    private fun showTtsSettingsSheet() {
        val controller = ttsController ?: return
        val dialog = BottomSheetDialog(this)
        val density = resources.displayMetrics.density
        val pad = (20 * density).roundToInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val sectionGap = (12 * density).roundToInt()
        root.addView(TextView(this).apply {
            text = getString(R.string.tts_settings)
            textSize = 16f
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            setPadding(0, 0, 0, sectionGap)
        })

        fun sliderRow(labelRes: Int, value: Float, max: Int, progress: Int, onChange: (Float) -> Unit, onStop: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, sectionGap)
            }
            val labelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            labelRow.addView(TextView(this).apply {
                text = getString(labelRes)
                textSize = 14f
                setTextColor(themeColor(android.R.attr.textColorPrimary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val valueView = TextView(this).apply {
                text = String.format(java.util.Locale.US, "%.2fx", value)
                textSize = 14f
                setTextColor(themeColor(android.R.attr.textColorSecondary))
            }
            labelRow.addView(valueView)
            row.addView(labelRow)
            val seek = SeekBar(this).apply {
                this.max = max
                this.progress = progress
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val v = if (labelRes == R.string.tts_speed) ttsRateFor(p) else ttsPitchFor(p)
                        valueView.text = String.format(java.util.Locale.US, "%.2fx", v)
                        onChange(v)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        onStop(sb?.progress ?: progress)
                    }
                })
            }
            row.addView(seek)
            return row
        }

        root.addView(sliderRow(
            R.string.tts_speed, controller.speechRate, PrefsManager.TTS_SPEED_MAX,
            (((controller.speechRate - 0.5f) / 0.05f).roundToInt()).coerceIn(0, PrefsManager.TTS_SPEED_MAX),
            onChange = { v -> controller.speechRate = v; scheduleTtsSettingsSave() },
            onStop = { p -> prefs.ttsSpeedProgress = p; scheduleTtsSettingsSave() },
        ))
        root.addView(sliderRow(
            R.string.tts_pitch, controller.pitch, PrefsManager.TTS_PITCH_MAX,
            (((controller.pitch - 0.5f) / 0.05f).roundToInt()).coerceIn(0, PrefsManager.TTS_PITCH_MAX),
            onChange = { v -> controller.pitch = v; scheduleTtsSettingsSave() },
            onStop = { p -> prefs.ttsPitchProgress = p; scheduleTtsSettingsSave() },
        ))

        val sleepButton = com.google.android.material.button.MaterialButton(this).apply {
            val remaining = controller.sleepTimerRemainingMs()
            text = if (remaining > 0L) getString(R.string.tts_sleep_remaining, (remaining / 60000L).toInt() + 1)
            else getString(R.string.tts_sleep_timer)
            setOnClickListener {
                showSleepTimerMenu()
                dialog.dismiss()
            }
        }
        root.addView(sleepButton)

        val backgroundSwitch = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            text = getString(R.string.tts_background_playback)
            isChecked = prefs.ttsBackgroundPlayback
            setOnCheckedChangeListener { _, checked ->
                if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                prefs.ttsBackgroundPlayback = checked
                updateTtsServiceState()
            }
        }
        root.addView(backgroundSwitch)
        root.addView(TextView(this).apply {
            text = getString(R.string.tts_background_playback_summary)
            textSize = 12f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(0, 0, 0, sectionGap)
        })
        val voiceButton = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.tts_voice)
            setOnClickListener { showVoicePicker() }
        }
        root.addView(voiceButton)
        dialog.setContentView(root)
        dialog.show()
    }

    /** Offline voices only (network-required voices are filtered out). */
    private fun showVoicePicker() {
        val controller = ttsController ?: return
        val voices = controller.availableVoices()
        val labels = mutableListOf(getString(R.string.tts_voice_default))
        val values = mutableListOf<String?>(null)
        voices.forEach { voice ->
            labels += "${voice.locale.displayName} · ${voice.name}"
            values += voice.name
        }
        val checked = values.indexOf(controller.voiceName).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.tts_voice)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                val wasPlaying = controller.state == ReaderTtsController.State.PLAYING
                controller.voiceName = values[which]
                scheduleTtsSettingsSave()
                // Patch v37 follow-up: if TTS is playing, pause it so the new
                // voice takes effect when the user presses play again. The
                // voice setter already applied the new voice to the engine;
                // pausing here ensures the current utterance stops.
                if (wasPlaying) {
                    controller.togglePauseResume()
                    updateTtsControlsUi(false)
                    Snackbar.make(
                        binding.root,
                        R.string.tts_voice_changed_pause,
                        Snackbar.LENGTH_SHORT,
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Loads per-book rate/pitch/voice from Room (falls back to app prefs).
     *  Patch v37: the sliders no longer live in the top bar (they moved into
     *  the settings sheet), so this only applies saved values to the controller;
     *  the sheet reads controller.speechRate / pitch each time it opens. */
    private fun applyTtsSettings() {
        val controller = ttsController ?: return
        controller.bookLanguage = epub?.metadata?.language
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = AppDatabase.get(applicationContext).ttsSettingsDao().getForBook(bookId)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (saved != null) {
                    controller.speechRate = saved.speechRate
                    controller.pitch = saved.pitch
                    controller.voiceName = saved.voiceName
                } else {
                    controller.speechRate = ttsRateFor(prefs.ttsSpeedProgress)
                    controller.pitch = ttsPitchFor(prefs.ttsPitchProgress)
                    controller.voiceName = null
                }
            }
        }
    }

    /** Debounced persist of per-book TTS settings after slider/voice changes. */
    private fun scheduleTtsSettingsSave() {
        handler.removeCallbacks(saveTtsSettingsRunnable)
        handler.postDelayed(saveTtsSettingsRunnable, TTS_SETTINGS_SAVE_DELAY_MS)
    }

    private fun saveTtsSettingsNow() {
        val controller = ttsController ?: return
        if (bookId < 0L) return
        val entity = TtsSettingsEntity(
            bookId = bookId,
            speechRate = controller.speechRate,
            pitch = controller.pitch,
            voiceName = controller.voiceName,
            updatedAt = System.currentTimeMillis(),
        )
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.get(applicationContext).ttsSettingsDao().upsert(entity)
        }
    }

    /** Bimodal reading: tints the sentence being spoken and the exact word
     *  being read, auto-turning the page when the spoken word moves off-page.
     *
     *  Patch v37: the highlight is drawn with non-mutating overlay rectangles
     *  (absolutely-positioned divs in a fixed container) instead of wrapping the
     *  text in <span>s. surroundContents/extractContents mutate the EPUB content
     *  tree, which reflowed the page and shifted the layout every time a new
     *  word was spoken — the user explicitly asked for the content to stay put.
     *  Overlay rects are positioned over the text and never touch the DOM, so
     *  pagination, columns and reflow are all left exactly as the user sees
     *  them. */
    private fun highlightSpokenWord(segment: com.epubreader.app.epub.ReaderTtsSegment, start: Int, end: Int) {
        if (isFinishing || isDestroyed) return
        val controller = ttsController ?: return
        if (controller.state != ReaderTtsController.State.PLAYING) return
        val color = getColor(R.color.tts_word_highlight)
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val wordCss = String.format(java.util.Locale.US, "rgba(%d,%d,%d,0.55)", r, g, b)
        val sentenceCss = String.format(java.util.Locale.US, "rgba(%d,%d,%d,0.22)", r, g, b)
        val segmentJson = org.json.JSONObject.quote(segment.text)
        val blockIndex = segment.blockIndex
        val blockStart = segment.blockTextStart
        val blockEnd = segment.blockTextEnd
        val wordStart = start.coerceIn(0, segment.text.length)
        val wordEnd = end.coerceIn(wordStart, segment.text.length)
        binding.webView.evaluateJavascript(
            """(function(){
                var spoken=$segmentJson,targetBlockIndex=$blockIndex,
                    sentenceStart=$blockStart,sentenceEnd=$blockEnd,
                    wordStart=$wordStart,wordEnd=$wordEnd,
                    wordColor='$wordCss',sentenceColor='$sentenceCss',doc=document,body=doc.body;
                if(!body)return;
                var c=doc.getElementById('livre-tts-hl');
                if(c){while(c.firstChild)c.removeChild(c.firstChild);}else{
                    c=doc.createElement('div');c.id='livre-tts-hl';
                    var cs=c.style;cs.position='fixed';cs.top='0';cs.left='0';
                    cs.width='100%';cs.height='100%';cs.pointerEvents='none';
                    cs.zIndex='2147483646';cs.overflow='hidden';body.appendChild(c);
                }
                var ignored={HEAD:1,SCRIPT:1,STYLE:1,NOSCRIPT:1,SVG:1,MATH:1};
                var blockTags={ADDRESS:1,ARTICLE:1,ASIDE:1,BLOCKQUOTE:1,DD:1,DIV:1,DL:1,DT:1,
                    FIGCAPTION:1,FIGURE:1,FOOTER:1,FORM:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,
                    HEADER:1,LI:1,MAIN:1,NAV:1,OL:1,P:1,PRE:1,SECTION:1,TABLE:1,TD:1,TH:1,TR:1,UL:1};
                function ignoredAncestor(el){while(el){if(ignored[el.tagName])return true;el=el.parentElement;}return false;}
                function hasBlockChild(el){
                    var kids=el.querySelectorAll('*');
                    for(var i=0;i<kids.length;i++)if(blockTags[kids[i].tagName]&&!ignoredAncestor(kids[i]))return true;
                    return false;
                }
                function normalizeWithMap(el){
                    var walker=doc.createTreeWalker(el,NodeFilter.SHOW_TEXT,null,false),
                        chars=[],map=[],n;
                    while(n=walker.nextNode()){
                        if(ignoredAncestor(n.parentElement))continue;
                        var t=n.textContent||'';
                        for(var i=0;i<t.length;i++){
                            var ch=t.charAt(i);
                            if(/\\s/.test(ch)){
                                if(chars.length&&chars[chars.length-1]!==' '){chars.push(' ');map.push({n:n,o:i});}
                            }else{chars.push(ch);map.push({n:n,o:i});}
                        }
                    }
                    while(chars.length&&chars[0]===' '){chars.shift();map.shift();}
                    while(chars.length&&chars[chars.length-1]===' '){chars.pop();map.pop();}
                    return {text:chars.join(''),map:map};
                }
                var blocks=[],all=body.querySelectorAll('*');
                for(var i=0;i<all.length;i++){
                    var el=all[i];
                    if(!blockTags[el.tagName]||ignoredAncestor(el)||hasBlockChild(el))continue;
                    var nm=normalizeWithMap(el);
                    if(nm.text)blocks.push({el:el,nm:nm});
                }
                var exact=[],spokenNorm=spoken.replace(/\\s+/g,' ').trim();
                for(var bi=0;bi<blocks.length;bi++){
                    if(blocks[bi].nm.text.indexOf(spokenNorm)>=0)exact.push(bi);
                }
                var chosen=-1;
                if(exact.length===1)chosen=exact[0];
                else if(exact.length>1){
                    // Prefer the structural block index when it is a valid exact match.
                    if(exact.indexOf(targetBlockIndex)>=0)chosen=targetBlockIndex;
                    else chosen=exact[0];
                }else if(blocks[targetBlockIndex]){
                    chosen=targetBlockIndex;
                }
                if(chosen<0||!blocks[chosen])return;
                var data=blocks[chosen].nm, text=data.text, pos=text.indexOf(spokenNorm);
                if(pos<0)return;
                // TTS offsets are relative to the normalized owning block. Clamp them
                // to the actual spoken sentence so whitespace normalization cannot
                // produce an invalid DOM Range.
                var sentenceAbsStart=Math.max(0,pos+sentenceStart);
                var sentenceAbsEnd=Math.min(text.length,pos+sentenceEnd);
                var wordAbsStart=Math.max(sentenceAbsStart,Math.min(sentenceAbsEnd,pos+wordStart));
                var wordAbsEnd=Math.max(wordAbsStart,Math.min(sentenceAbsEnd,pos+wordEnd));
                function point(at,endPoint){
                    if(!data.map.length)return null;
                    if(at>=data.map.length){var last=data.map[data.map.length-1];return [last.n,(last.n.textContent||'').length];}
                    var p=data.map[Math.max(0,at)];return [p.n,endPoint?Math.min((p.n.textContent||'').length,p.o+1):p.o];
                }
                function range(a,b){if(!a||!b)return null;try{var q=doc.createRange();q.setStart(a[0],a[1]);q.setEnd(b[0],b[1]);return q;}catch(e){return null;}}
                function draw(rng,color){
                    if(!rng)return null;var rects=rng.getClientRects(),last=null;
                    for(var i=0;i<rects.length;i++){var z=rects[i];if(z.width<=0||z.height<=0)continue;
                        var d=doc.createElement('div'),s=d.style;s.position='fixed';s.left=z.left+'px';s.top=z.top+'px';
                        s.width=z.width+'px';s.height=z.height+'px';s.backgroundColor=color;s.borderRadius='2px';c.appendChild(d);last=z;}
                    return last;
                }
                draw(range(point(sentenceAbsStart,false),point(sentenceAbsEnd,true)),sentenceColor);
                if(wordAbsStart>=wordAbsEnd)return;
                var rect=draw(range(point(wordAbsStart,false),point(wordAbsEnd,true)),wordColor);
                if(rect&&window.Caesura){if(rect.right>window.innerWidth-4)window.Caesura.nextPage(false);else if(rect.left<4)window.Caesura.prevPage(false);}
            })();""",
            null,
        )
    }

    private fun clearSpokenWordHighlight() {
        if (isFinishing || isDestroyed) return
        // Patch v37: removes the non-mutating overlay container created in
        // highlightSpokenWord. No EPUB content was wrapped, so there is nothing
        // to unwrap — just drop the overlay divs.
        binding.webView.evaluateJavascript(
            "(function(){var c=document.getElementById('livre-tts-hl');if(c&&c.parentNode)c.parentNode.removeChild(c);})();",
            null,
        )
    }

    private fun setupChrome() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnToc.setOnClickListener { showTocBookmarks() }
        binding.btnBookmarks.setOnClickListener { showTocBookmarks(selectBookmarks = true) }
        binding.btnHighlights.setOnClickListener { showTocBookmarks(selectHighlights = true) }
        binding.btnSearch.setOnClickListener { showSearchOverlay() }
        binding.btnSettings.setOnClickListener { showSettings() }
        // Patch v37: the TTS transport lives in its own full-screen overlay now
        // (see showTtsOverlay / hideTtsOverlay). The top bar's speaker button
        // only opens that overlay (or starts playback if idle) — it no longer
        // toggles pause, because pause lives in the overlay's own play/pause
        // button. The overlay's transport (play/pause, prev/next sentence, stop,
        // settings) is wired here. Speed + pitch sliders moved into
        // showTtsSettingsSheet so the overlay stays clean.
        binding.btnReadAloud.setOnClickListener {
            val state = ttsController?.state
            if (state == ReaderTtsController.State.PLAYING || state == ReaderTtsController.State.PAUSED) {
                // Already running: bring the overlay back (it may have been
                // dismissed) rather than toggling pause from the top bar.
                showTtsOverlay()
            } else {
                showTtsOverlay()
                startTtsForCurrentChapter()
            }
        }
        binding.btnTtsClose.setOnClickListener { hideTtsOverlay() }
        binding.btnTtsPlayPause.setOnClickListener { ttsController?.togglePauseResume() }
        binding.btnTtsPrev.setOnClickListener {
            ttsController?.skipSentence(forward = false)
        }
        binding.btnTtsNext.setOnClickListener {
            ttsController?.skipSentence(forward = true)
        }
        binding.btnTtsStop.setOnClickListener { stopTtsCompletely() }
        binding.btnTtsSettings.setOnClickListener { showTtsSettingsSheet() }

        // Seed the engine rate/pitch from app prefs as a fallback before the
        // per-book Room settings load (applyTtsSettings). The settings sheet is
        // the single place sliders are shown now.
        ttsController?.speechRate = ttsRateFor(prefs.ttsSpeedProgress)
        ttsController?.pitch = ttsPitchFor(prefs.ttsPitchProgress)
        binding.tvAddBookmark.setOnClickListener { addBookmark() }
        binding.readerHistoryBack.setOnClickListener { goBackInReaderHistory() }
        binding.readerHistoryForward.setOnClickListener { goForwardInReaderHistory() }
        binding.readerHistoryClear.setOnClickListener { clearReaderHistory() }
        updateHistoryUi()

        binding.seekChapter.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    userSeeking = true
                    manualSeekFinished = false
                    pendingSeekProgress = sb?.progress
                    progressRequestToken++
                }

                override fun onProgressChanged(
                    sb: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    pendingSeekProgress = progress
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    if (manualSeekFinished) {
                        manualSeekFinished = false
                        return
                    }
                    finishSeek(sb?.progress ?: pendingSeekProgress)
                }
            }
        )

        // Let the platform SeekBar own the drag gesture. The only custom touch
        // handling is on ACTION_UP: correct the final thumb position from the
        // actual touch coordinate before the normal OnStopTrackingTouch callback
        // resolves the seek. This preserves the existing tap behavior and avoids
        // intercepting the drag stream or issuing repeated WebView navigations.
        binding.seekChapter.setOnTouchListener { view, event ->
            val sb = view as SeekBar
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    manualSeekTouch = true
                    userSeeking = true
                    manualSeekFinished = false
                    progressRequestToken++
                    false
                }

                MotionEvent.ACTION_UP -> {
                    if (manualSeekTouch) {
                        val finalProgress = progressForSeekTouch(sb, event.x)
                        if (finalProgress != sb.progress) {
                            sb.progress = finalProgress
                        }
                        pendingSeekProgress = finalProgress
                    }
                    manualSeekTouch = false
                    false
                }

                MotionEvent.ACTION_CANCEL -> {
                    manualSeekTouch = false
                    pendingSeekProgress = null
                    userSeeking = false
                    false
                }

                else -> false
            }
        }



        val detector =
            android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    // Patch v37: don't turn the page or toggle chrome when a
                    // modal overlay (TOC / Bookmarks / Search) is open.
                    if (overlayVisible()) return true
                    // Patch v37: don't turn the page or toggle chrome when this
                    // tap landed on a highlight (the HighlightBridge already
                    // opened the note/delete sheet) or just dismissed a text
                    // selection.
                    if (shouldSuppressReaderTap()) return true
                    // Patch 16 (Issue #2): the user reported that tapping a link inside
                    // a TOC (rendered as html/xhtml content in the reader) registers
                    // BOTH the link click AND a page turn (left third -> back, right
                    // third -> forward) because the WebView touch listener returns
                    // false, so the WebView also processes the tap natively as a link
                    // click. Before deciding whether this tap is a page turn / chrome
                    // toggle, check whether it landed on a hyperlink; if it did, let the
                    // WebView handle it natively (navigateToUrl) and do NOT turn the
                    // page or toggle chrome. A tap on the slider/button area also lands
                    // here but should not jump pages either — so for any non-page region
                    // tap that hit an anchor, defer to the web view.
                    if (tappedLinkOnWebView(e)) return true
                    val w = binding.webView.width.toFloat()
                    if (w <= 0f) {
                        toggleChrome(); return true
                    }
                    // Patch v37: when the reader settings menu (chrome) is
                    // visible, a tap only hides it — it never turns the page.
                    if (chromeVisible) {
                        toggleChrome(); return true
                    }
                    when {
                        e.x < w / 3f -> turnPage(false)
                        e.x > w * 2f / 3f -> turnPage(true)
                        else -> toggleChrome()
                    }
                    return true
                }

                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    val start = e1 ?: return false
                    val dx = e2.x - start.x
                    val dy = e2.y - start.y
                    if (kotlin.math.abs(dx) < 80f) return false
                    if (kotlin.math.abs(dx) < kotlin.math.abs(dy)) return false
                    if (kotlin.math.abs(velocityX) < 500f) return false
                    if (dx < 0) turnPage(true) else turnPage(false)
                    return true
                }
            })
        binding.webView.setOnTouchListener { _, event ->
            // Do not call ActionMode.hide() from WebView touch dispatch. The native
            // ActionMode is owned by Chromium and hiding it during a selection touch
            // can crash on some Android versions. The Activity-level menu suppression
            // above keeps its action toolbar empty without disturbing selection.
            // Patch v37: a fresh tap that lands while a text selection is active
            // dismisses the selection. The entire gesture is consumed (never
            // reaches the GestureDetector) so it does NOT also turn the page or
            // toggle the reader chrome — the user explicitly asked that
            // unselecting text leave the page and chrome untouched.
            if (event.actionMasked == MotionEvent.ACTION_DOWN && currentSelectionActionMode != null) {
                consumingSelectionDismissTap = true
                suppressReaderTap()
                clearReaderSelection()
            }
            if (consumingSelectionDismissTap) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    consumingSelectionDismissTap = false
                }
                return@setOnTouchListener true
            }
            detector.onTouchEvent(event); false
        }
    }


    private fun progressForSeekTouch(sb: SeekBar, x: Float): Int {
        val usableWidth = (sb.width - sb.paddingLeft - sb.paddingRight).coerceAtLeast(1)
        val localX = (x - sb.paddingLeft).coerceIn(0f, usableWidth.toFloat())
        val fraction = localX / usableWidth.toFloat()
        return (fraction * sb.max).roundToInt().coerceIn(0, sb.max)
    }

    private fun finishSeek(target: Int?) {
        val resolved = target ?: sbProgressFallback()
        pendingSeekProgress = null
        userSeeking = false
        if (resolved == null) {
            handler.postDelayed({ pollProgress() }, 100L)
            return
        }

        val current = captureReaderLocation()
        val targetLocation = locationForAbsolutePage(resolved)
        if (!restoringHistoryLocation && current != null && targetLocation != null) {
            if (!sameLocation(current, targetLocation)) {
                pushHistory(current)
                // A seek-bar jump is an explicit navigation jump. Keep the history
                // cursor on the selected destination so ordinary page swipes after
                // the jump do not replace it with the later live page.
                historyCursorLocation = targetLocation
            }
        }

        if (perPageSeekerActive) {
            pendingExactSeekLocation = targetLocation
            exactSeekUiLocation = pendingExactSeekLocation
            exactSeekUiLocation?.let { target ->
                currentPageInChapter = target.pageInChapter
                currentScrollRatio = target.ratio
                updatePageIndicator()
                updateOverallProgress()
            }
            seekToAbsolutePage(resolved)
        } else {
            pendingExactSeekLocation = null
            goToSpine(resolved)
        }

        // A poll can already be queued from immediately before the seek. The
        // exact seek result is authoritative until the WebView reports that same
        // page back to us.
        progressRequestToken++
        handler.postDelayed({ pollProgress() }, 150L)
    }

    private fun sbProgressFallback(): Int? =
        binding.seekChapter.progress.takeIf { binding.seekChapter.max >= 0 }

    private fun captureReaderLocation(): ReaderLocation? {
        if (spineIndex < 0 || spineIndex >= (epub?.spine?.size ?: 0)) return null
        return ReaderLocation(spineIndex, currentPageInChapter.coerceAtLeast(0), currentScrollRatio.coerceIn(0f, 1f))
    }

    private fun locationForAbsolutePage(absolute: Int): ReaderLocation? {
        val counts = chapterPageCounts ?: return null
        if (counts.isEmpty() || counts.any { it < 0 }) return null
        val (spine, page) = ReaderPageMapping.spineAndPageFor(counts, absolute)
        val count = counts[spine].coerceAtLeast(1)
        val ratio = if (count > 1) page / (count - 1).toFloat() else 0f
        return ReaderLocation(spine, page, ratio)
    }

    private fun sameLocation(a: ReaderLocation, b: ReaderLocation): Boolean =
        a.spineIndex == b.spineIndex &&
                kotlin.math.abs(a.ratio - b.ratio) < 0.01f &&
                a.pageInChapter == b.pageInChapter

    private fun pushHistory(location: ReaderLocation) {
        val last = backHistory.lastOrNull()
        if (last != null && sameLocation(last, location)) return
        backHistory.addLast(location)
        forwardHistory.clear()
        updateHistoryUi()
    }

    private fun goBackInReaderHistory() {
        if (backHistory.isEmpty()) return
        val target = backHistory.removeLast()
        val current = historyCursorLocation ?: captureReaderLocation()
        if (current != null) forwardHistory.addLast(current)
        historyCursorLocation = target
        restoringHistoryLocation = true
        updateHistoryUi()
        navigateToReaderLocation(target)
    }

    private fun goForwardInReaderHistory() {
        if (forwardHistory.isEmpty()) return
        val target = forwardHistory.removeLast()
        val current = historyCursorLocation ?: captureReaderLocation()
        if (current != null) backHistory.addLast(current)
        historyCursorLocation = target
        restoringHistoryLocation = true
        updateHistoryUi()
        navigateToReaderLocation(target)
    }

    private fun clearReaderHistory() {
        backHistory.clear()
        forwardHistory.clear()
        updateHistoryUi()
    }

    private fun navigateToReaderLocation(location: ReaderLocation) {
        val count = chapterPageCounts?.getOrNull(location.spineIndex)?.coerceAtLeast(1) ?: 1
        val ratio = if (count > 1) location.pageInChapter.coerceIn(0, count - 1) / (count - 1).toFloat() else location.ratio
        restoreRatio = ratio.coerceIn(0f, 1f)
        historyCursorLocation = location
        pendingHistoryCursorAfterRestore = false
        if (location.spineIndex == spineIndex) {
            binding.webView.evaluateJavascript(
                "if(window.Caesura){window.Caesura.gotoPage(${location.pageInChapter.coerceAtLeast(0)},false);}"
            ) {
                restoringHistoryLocation = false
                handler.postDelayed({ pollProgress() }, 80L)
                updateHistoryUi()
            }
        } else {
            loadChapter(location.spineIndex)
        }
    }

    private fun historyPageLabel(location: ReaderLocation): String {
        val counts = chapterPageCounts
        if (counts != null && counts.isNotEmpty() && counts.none { it < 0 }) {
            val prefix = ReaderPageMapping.prefixSums(counts)
            val page = (prefix.getOrNull(location.spineIndex) ?: 0) + location.pageInChapter + 1
            return page.toString()
        }
        return (location.pageInChapter + 1).toString()
    }

    private fun updateHistoryUi() {
        val hasBack = backHistory.isNotEmpty()
        val hasForward = forwardHistory.isNotEmpty()
        binding.readerHistory.setBackgroundColor(readerColors().first)
        val showHistory = (hasBack || hasForward) && chromeVisible && !overlayVisible()
        binding.readerHistory.visibility = if (showHistory) View.VISIBLE else View.GONE
        binding.readerHistoryBack.visibility = if (hasBack) View.VISIBLE else View.INVISIBLE
        binding.readerHistoryForward.visibility = if (hasForward) View.VISIBLE else View.INVISIBLE
        if (hasBack) binding.readerHistoryBack.text = getString(R.string.reader_history_back, historyPageLabel(backHistory.last()))
        if (hasForward) binding.readerHistoryForward.text = getString(R.string.reader_history_forward, historyPageLabel(forwardHistory.last()))
    }
    // Patch 16 (Issue #2): returns true if the confirmed tap landed on a link
    // inside the WebView. hitTestResult is queried immediately (the touch is
    // still in the DOWN -> UP window when onSingleTapConfirmed fires), and an
    // SRC_ANCHOR / SRC_IMAGE_ANCHOR result is the explicit signal that the tap
    // is on a hyperlink. This lets the WebView's native click handling run for
    // the link while suppressing the page turn the gesture detector would
    // otherwise trigger. Without this, tapping a TOC entry navigates to the
    // link's destination AND also advances/retreats a page.
    private fun tappedLinkOnWebView(e: MotionEvent): Boolean {
        val hit = binding.webView.hitTestResult
        val type = hit?.type ?: android.webkit.WebView.HitTestResult.UNKNOWN_TYPE
        return type == android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
    }

    private var pageTurnThrottleUntil = 0L

    /** Patch 17 (Addition #2): remembered turn direction for the snapshot slide,
     *  + a token that invalidates a stale dismiss end-action when a newer capture
     *  supersedes an in-flight slide (so the older cleanup never recycles the
     *  newer bitmap). */
    private var snapshotForward = true
    private var snapshotAnimToken = 0
    private fun turnPage(forward: Boolean) {
        if (overlayVisible()) return
        // Patch v37: don't turn the page while the reader settings menu (chrome)
        // is visible — the user explicitly asked that the page not change then.
        if (chromeVisible) return
        val now = System.currentTimeMillis()
        if (now < pageTurnThrottleUntil) return
        // Patch 17 (Addition #2): throttle now matches the (longer) slide
        // animation duration so two rapid taps can never stack two snapshot
        // animations on top of each other.
        pageTurnThrottleUntil = now + PAGE_TURN_DURATION_MS + 40L
        performPageTurn(forward)
    }

    private fun performPageTurn(forward: Boolean) {
        // Patch 9: the same crossfade transition used for cross-chapter / TOC /
        // seeker jumps is now applied to in-chapter page turns too. Capture the
        // current page, advance instantly (no scroll animation), then crossfade
        // the snapshot out to reveal the new page.
        //
        // Patch 17 (Addition #2): the snapshot is now SLID out horizontally
        // (forward -> off the left edge, back -> off the right edge) with a short
        // alpha fade, reading like turning a physical page. Gated by the
        // Page Turn Animation setting; when off, the snapshot is cleared
        // instantly (no animation). [forward] is remembered so every dismiss
        // path (chapter cross, restore, seeker) can reuse the same direction.
        capturePageSnapshot(forward)
        val js = if (forward) "(function(){return window.Caesura?window.Caesura.nextPage(false):'not-ready';})()"
        else "(function(){return window.Caesura?window.Caesura.prevPage(false):'not-ready';})()"
        binding.webView.evaluateJavascript(js) { result ->
            when (result?.trim('\"')) {
                "next-chapter" -> goToSpine(spineIndex + 1)
                "prev-chapter" -> {
                    restoreRatio = 1.0f; goToSpine(spineIndex - 1)
                }

                "not-ready" -> {
                    dismissPageSnapshot(forward); handler.postDelayed({ performPageTurn(forward) }, 120)
                }

                else -> dismissPageSnapshot(forward)  // 'ok': new page positioned — slide/clear snapshot
            }
        }
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        binding.topBar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        updateHistoryUi()
        binding.tvPageIndicator.visibility =
            if (!chromeVisible && !overlayVisible()) View.VISIBLE else View.GONE
        if (chromeVisible) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else if (!prefs.keepScreenOn) {
            // Only allow the screen to time out while reading if the user has
            // NOT enabled "keep screen on". Previously this cleared the flag
            // unconditionally, which defeated the keep-screen-on setting as
            // soon as the chrome was hidden — the screen would turn off at
            // the system timeout even with the setting on.
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        updatePageIndicator()
    }

    // ---------------------------------------------------------------- load book
    private fun loadBook() {
        lifecycleScope.launch(Dispatchers.IO) {
            val entity = db.bookDao().getById(bookId) ?: return@launch
            bookEntity = entity
            val file = File(entity.path)
            if (!file.exists()) return@launch
            val parser = EpubParser()
            val parsed = try {
                parser.parse(file)
            } catch (_: Exception) {
                null
            } ?: return@launch
            epub = parsed
            // Keep the legacy spine count synchronized for reader compatibility.
            // Home uses the embedded navigation TOC location instead of a chapter count.
            if (entity.spineCount != parsed.spine.size) {
                db.bookDao().updateSpineCount(bookId, parsed.spine.size)
            }
            resolver = EpubResourceResolver(file)
            buildTocSectionMap(parsed)
            spineIndex = entity.spineIndex.coerceIn(0, parsed.spine.lastIndex)
            restoreRatio = entity.scrollRatio.takeIf { it > 0f }

            // Patch 7 behavior: page counts come from a REAL offscreen layout pass
            // (the measureWebView), not the ADE byte-map. The total therefore
            // reflects the current font / size / margins / line-height and changes
            // when you change reader settings — one screen page == one book page,
            // like Calibre / Readium. Counts start at -1 ("not measured yet") and
            // are filled in chapter-by-chapter in the background; the bottom
            // seeker switches to per-page once every chapter has been measured.
            // (Patch 8's EpubPageMap / page_map_csv instant-stable totals are no
            // longer used for the reader; the DB column is left in place so
            // existing installs don't need a schema downgrade.)
            chapterPageCounts = IntArray(parsed.spine.size) { -1 }

            withContext(Dispatchers.Main) {
                bindBookHeader(parsed)
                // Patch v37: per-book read-aloud settings (rate/pitch/voice)
                // plus the book's language for multilingual TTS + dictionary.
                applyTtsSettings()
                perPageSeekerActive = false
                binding.seekChapter.max = (parsed.spine.size - 1).coerceAtLeast(0)
                binding.seekChapter.progress = spineIndex
                loadChapter(spineIndex, resetRatio = false)
                binding.tvPageIndicator.visibility = View.VISIBLE

                // Exact rendered page totals are inherently layout-dependent: the
                // visible WebView knows the current chapter immediately, but the
                // total book page count requires every spine item to be paginated.
                // Reuse the persisted screen-page map when the layout fingerprint
                // matches; this makes app reopen / book reopen instantaneous. A new
                // book or a new layout still needs the one-time background pass.
                binding.webView.post {
                    val cached = loadCachedScreenPageCounts(entity)
                    if (!cached) {
                        startMeasurement()
                    }
                    updatePageIndicator()
                    updateSectionPages()
                }
                updatePageIndicator()
                updateSectionPages()
                beginReadingSession(System.currentTimeMillis())
            }
        }
    }

    private fun bindBookHeader(book: EpubBook) {
        val m = book.metadata
        binding.tvBookTitle.text = m.title.ifBlank { getString(R.string.reader_contents) }
        val authorSeries = buildString {
            append(m.authorString)
            val s = m.series
            if (!s.isNullOrBlank()) {
                append("  —  ").append(s)
                m.seriesIndex?.let { idx ->
                    val n = idx.toInt()
                    append(" #$n")
                }
            }
        }
        binding.tvAuthorSeries.text = authorSeries
        binding.tvOverlayTitle.text = m.title.ifBlank { getString(R.string.reader_contents) }
    }

    /** Build the spine -> section-label map from the embedded nav TOC (NOT toc.xhtml). */
    private fun buildTocSectionMap(book: EpubBook) {
        val bySpine = LinkedHashMap<Int, String>()
        for (e in book.toc) {
            val path = normalizeTocHref(e.href)
            if (path.isBlank()) continue
            val idx = book.spine.indexOfFirst { normalizeTocHref(it.href) == path }
            if (idx >= 0 && !bySpine.containsKey(idx)) bySpine[idx] = e.label.trim()
        }
        tocSectionMap = bySpine
        tocSections = bySpine.toList().sortedBy { it.first }
    }

    private fun normalizeTocHref(href: String): String =
        href.substringBefore('#').substringBefore('?').trimStart('/').trimEnd('/')

    private fun currentTocPosition(): Int {

        val book = epub
            ?: return RecyclerView.NO_POSITION

        var bestPosition =
            RecyclerView.NO_POSITION

        var bestSpineIndex =
            -1

        for (position in book.toc.indices) {

            val entry =
                book.toc[position]

            val tocPath =
                entry.href
                    .substringBefore('#')
                    .substringBefore('?')
                    .trimStart('/')

            val entrySpineIndex =
                book.spine.indexOfFirst {
                    it.href
                        .substringBefore('#')
                        .substringBefore('?')
                        .trimStart('/') == tocPath
                }

            if (
                entrySpineIndex >= 0 &&
                entrySpineIndex <= spineIndex &&
                (
                        entrySpineIndex > bestSpineIndex ||
                                (
                                        entrySpineIndex == bestSpineIndex &&
                                                position > bestPosition
                                        )
                        )
            ) {
                bestSpineIndex =
                    entrySpineIndex

                bestPosition =
                    position
            }
        }

        return bestPosition
    }

    private fun highlightCurrentTocEntry() {

        val recycler =
            tocRv
                ?: return

        val adapter =
            recycler.adapter
                    as? TocAdapter
                ?: return

        val position =
            currentTocPosition()

        adapter.setSelectedPosition(
            position
        )

        if (
            position == RecyclerView.NO_POSITION
        ) {
            return
        }

        recycler.post {

            if (
                position !in 0 until adapter.itemCount
            ) {
                return@post
            }

            val layoutManager =
                recycler.layoutManager
                        as? LinearLayoutManager
                    ?: return@post

            val offset =
                (
                        recycler.height * 0.35f
                        ).toInt()

            layoutManager.scrollToPositionWithOffset(
                position,
                offset
            )
        }
    }

    /** Persist only the embedded navigation TOC heading for the current reader location. */
    private fun persistCurrentTocLocation() {
        val location = tocSectionMap[spineIndex]
            ?: tocSections.lastOrNull { it.first <= spineIndex }?.second
            ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            db.bookDao().updateCurrentLocation(bookId, location)
        }
    }

    /** Section label for the current spine item (embedded nav, nearest-previous fallback). */
    private fun sectionLabel(): String {
        val idx = spineIndex
        tocSectionMap[idx]?.let { return it }
        // nearest previous toc entry
        val prev = tocSections.lastOrNull { it.first <= idx }?.second
        if (!prev.isNullOrBlank()) return prev
        // fallback: derive from filename or position
        val href = epub?.spine?.getOrNull(idx)?.href
        val base = href?.substringAfterLast('/')?.substringBeforeLast('.')?.replace('_', ' ')?.replace('-', ' ')
        return base?.replaceFirstChar { it.uppercase() } ?: getString(R.string.reader_chapter, idx + 1)
    }

    // ---------------------------------------------------------------- chapter rendering
    private fun loadChapter(index: Int, resetRatio: Boolean = true) {
        clearReaderSelection()
        val book = epub ?: return
        if (index !in book.spine.indices) return
        val crossing = index != spineIndex
        if (crossing) capturePageSnapshot(forward = index > spineIndex)  // keep the old page visible while the next loads
        spineIndex = index
        persistCurrentTocLocation()
        // While per-page seeking is active the seeker's max is total pages, so
        // don't reset progress to a raw spine index here — syncSeekBarFromCurrentPage()
        // (called via the poller / reveal) keeps it on the right absolute page.
        if (!perPageSeekerActive) binding.seekChapter.progress = index
        if (resetRatio) currentScrollRatio = 0f
        handler.removeCallbacks(alphaFallback)

        val item = book.spine[index]
        val html = buildChapterHtml(item.href) ?: return
        val baseUrl = EpubResourceResolver.baseUrl(bookId, item.href)
        // Hide until the restored page is set, to avoid the page-0 flash.
        // Incrementing the generation invalidates callbacks from an older chapter load.
        readerGeneration += 1
        activeReaderGeneration = readerGeneration

        binding.webView.alpha = 0f
        binding.webView.loadDataWithBaseURL(
            baseUrl,
            html,
            "text/html",
            "UTF-8",
            null
        )
        binding.tvSectionPages.text = sectionLabel()

        if (binding.tocBookmarkOverlay.visibility == View.VISIBLE) {
            highlightCurrentTocEntry()
        }

        updateOverallProgress()
        updatePageIndicator()
    }

    private fun buildChapterHtml(entryPath: String): String? {
        val res = resolver ?: return null
        val raw = res.resolve(entryPath)?.bufferedReader()?.use { it.readText() } ?: return null
        val css = buildReaderCss()
        val js = paginationJs(bottomGuardPx(), topGuardPx())
        val head = "$css$js"
        return if (raw.contains("</head>", ignoreCase = true)) {
            raw.replaceFirst("(?i)</head>".toRegex(), "$head</head>")
        } else if (raw.contains("<html", ignoreCase = true)) {
            raw.replaceFirst("(?i)<html".toRegex(), "<head>$head</head><html")
        } else {
            "<head>$head</head>$raw"
        }
    }

    private fun buildReaderCss(): String {
        val (_, ink) = readerColors()
        val (bg, _) = readerColors()
        // PUBLISHER = use the book's own fonts: do NOT emit a font-family override
        // so the EPUB's @font-face + font-family declarations (served by
        // EpubResourceResolver with correct font MIME types) take effect.
        val fontFamily = when (prefs.font) {
            PrefsManager.Font.SANS -> "system-ui, -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"
            PrefsManager.Font.MONO -> "'Courier New', Consolas, monospace"
            PrefsManager.Font.BOOK -> "Palatino, 'Palatino Linotype', 'Book Antiqua', Georgia, serif"
            PrefsManager.Font.HUMANIST -> "Tahoma, Verdana, Geneva, 'Segoe UI', sans-serif"
            PrefsManager.Font.PUBLISHER -> null
            else -> "Georgia, 'Times New Roman', serif"
        }
        // ORIGINAL = honor the book's own alignment (no text-align override).
        val alignCss = when (prefs.align) {
            PrefsManager.Align.JUSTIFY -> "text-align:justify !important;"
            PrefsManager.Align.CENTER -> "text-align:center !important;"
            PrefsManager.Align.RIGHT -> "text-align:right !important;"
            PrefsManager.Align.LEFT -> "text-align:left !important;"
            else -> ""
        }
        val hyphensCss = if (prefs.hyphenation)
            "-webkit-hyphens:auto;hyphens:auto;" else "-webkit-hyphens:manual;hyphens:manual;"
        val size = prefs.fontSize
        val line = prefs.lineHeight
        val margin = prefs.margin
        val fontCss = fontFamily?.let { "font-family:$it !important;" } ?: ""
        return """<style>
html, body {
  background:${ColorToHex(bg)} !important;
  color:${ink} !important;
  margin:0 !important;
  overflow-x:hidden !important;
  overflow-y:hidden !important;
  -webkit-tap-highlight-color:transparent;
}
body {
  $fontCss
  font-size:${size}px !important;
  line-height:$line !important;
  $alignCss
  padding:0 ${margin}px !important;
  column-fill:auto !important;
  -webkit-column-fill:auto !important;
  column-gap:${2 * margin}px !important;
  -webkit-column-gap:${2 * margin}px !important;
  word-wrap:break-word;
  -webkit-text-size-adjust:100%;
  $hyphensCss
}
/* Patch 7-style image handling (reverted from Patch 8's object-fit/max-height
   block, which made inline icons render at full viewport height and "overpower"
   the surrounding text). We now only constrain horizontal overflow and let the
   EPUB's own CSS decide inline/block sizing, exactly like Calibre's editor /
   Readium: inline icons stay inline and natural-sized, large images scale to
   the column width while preserving aspect ratio. No max-height, no
   object-fit, no forced break-avoid on inline media. */
img, svg, video {
  max-width:100% !important;
  height:auto !important;
}
figure { margin:0.5em 0 !important; }
table { max-width:100% !important; }
a { color:${ink} !important; }
h1,h2,h3,h4,h5,h6 { color:${ink} !important; line-height:1.25 !important; break-after:avoid; }
/* Highlight marks: subtle background, no layout disruption. */
mark.livre-highlight {
  color:inherit !important;
  break-inside:avoid;
  -webkit-column-break-inside:avoid;
}
</style>""".trimIndent() + darkTextOverride(prefs.theme, ink)
    }

    /**
     * Patch 10: in DARK mode, some EPUBs hard-code a dark text color via an
     * inline `style="color:#..."` or a stylesheet rule. The `body { color:ink
     * !important }` rule only sets the *inherited* default; an explicit color
     * declared on a descendant (e.g. `<p style="color:#333">`) wins by
     * specificity and renders dark text on a dark page. To match what most
     * dark-mode readers do, in dark mode only we force the ink color down
     * through EVERY element (including inline-styled ones), so no dark-on-dark
     * text survives. This is applied only in dark mode so light/sepia books are
     * untouched. Images are not affected (they don't use `color`).
     */
    private fun darkTextOverride(theme: String, ink: String): String {
        // Patch 13/14: apply in dark AND sepia. In both, a hard-coded light/white
        // `background-color` on a heading (e.g. a "1" badge or all-caps title) becomes a
        // visible white bar: in light mode that white box blends into the white page so
        // it's invisible and we leave light mode untouched; in dark/sepia the box stands
        // out (and, in dark, the forced-white text disappears into it). Forcing descendant
        // backgrounds to transparent (leaving `body`'s own page background, set in
        // buildReaderCss, intact) removes those bars. We also push the ink color through
        // every element so hard-coded text colors don't survive into a tinted/dark page.
        // Colored callout boxes flatten in dark/sepia — the standard reader tradeoff.
        //
        // Patch 17 (Addition #1): the decision is now driven by the theme registry's
        // [ReaderTheme.needsInkOverride] flag (true for every non-Ivory theme) so new
        // tinted/dark themes get the same protection automatically.
        if (!ReaderTheme.byId(theme).needsInkOverride) return ""
        return """
<style>
body, body * { color:${ink} !important; }
body *:not(mark.livre-highlight):not(.livre-tts-word):not(.livre-tts-sentence) { background-color: transparent !important; }
</style>""".trimIndent()
    }

    /** Pagination JS. Key fix for the "next-page sliver": columns are `column-gap = 2*margin`
     *  wide and each page advances by the full viewport width (innerWidth), so column N+1
     *  begins exactly at the right edge of the viewport and never peeks into page N.
     *  Bottom guard shrinks body height to reserve space for the page indicator. */
    private fun paginationJs(guardPx: Int, topGuardPx: Int): String {
        return """
        <script>
        (function () {
          var body = null;
          var html = document.documentElement;
          var GUARD = $guardPx;
          var TOP_GUARD = $topGuardPx;

          function px(v) {
            return parseFloat(v) || 0;
          }

          function viewportW() {
            return window.innerWidth || html.clientWidth || 1;
          }

          function padX() {
            var cs = getComputedStyle(body);
            return px(cs.paddingLeft) + px(cs.paddingRight);
          }

          function gapW() {
            var cs = getComputedStyle(body);
            return px(cs.columnGap) || 0;
          }

          function colW() {
            return Math.max(1, viewportW() - padX());
          }

          function advance() {
            return colW() + gapW();
          }

          function pageCount() {
            if (!body) return 1;
            var scrollWidth = body.scrollWidth || body.offsetWidth || 1;
            return Math.max(1, Math.round(scrollWidth / advance()));
          }

          function currentPage() {
            if (!body) return 0;
            return Math.max(0, Math.round(body.scrollLeft / advance()));
          }

          function animateTo(target, duration) {
            if (!body) return;

            var start = body.scrollLeft || 0;
            var delta = target - start;
            if (delta === 0) return;

            var startedAt = performance.now();

            function ease(t) {
              return t < 0.5
                ? 2 * t * t
                : 1 - Math.pow(-2 * t + 2, 2) / 2;
            }

            function frame(now) {
              var progress = Math.min(1, (now - startedAt) / duration);
              body.scrollLeft = start + delta * ease(progress);

              if (progress < 1) {
                requestAnimationFrame(frame);
              }
            }

            requestAnimationFrame(frame);
          }

          function gotoPage(page, animate) {
            if (!body) return 0;

            var count = pageCount();
            var safePage = Math.max(
              0,
              Math.min(Math.floor(page), count - 1)
            );

            var target = safePage * advance();

            if (animate) {
              animateTo(target, 240);
            } else {
              body.scrollLeft = target;
            }

            return safePage;
          }

          function nextPage(animate) {
            var count = pageCount();
            var page = currentPage();

            if (page >= count - 1) return 'next-chapter';

            gotoPage(page + 1, animate);
            return 'ok';
          }

          function prevPage(animate) {
            var page = currentPage();

            if (page <= 0) return 'prev-chapter';

            gotoPage(page - 1, animate);
            return 'ok';
          }

          function ratio() {
            var count = pageCount();
            if (count <= 1) return 0;
            return currentPage() / (count - 1);
          }

          function pageForElementById(id) {
            var element = document.getElementById(id);

            if (!element) {
              var named = document.getElementsByName(id);
              if (named.length) element = named[0];
            }

            if (!element) return -1;

            var x = 0;
            var node = element;

            while (node) {
              x += node.offsetLeft || 0;
              node = node.offsetParent;
            }

            return Math.floor(x / advance());
          }

          function gotoElementById(id) {
            var page = pageForElementById(id);
            if (page < 0) return false;
            gotoPage(page, false);
            return true;
          }

          function highlightPageById(id) {
            var marks = document.querySelectorAll('mark.livre-highlight[data-highlight-id="' + id + '"]');
            if (!marks.length) return -1;
            var element = marks[0];
            var x = 0;
            var node = element;
            while (node) {
              x += node.offsetLeft || 0;
              node = node.offsetParent;
            }
            return Math.floor(x / advance());
          }

          function gotoHighlightById(id) {
            var page = highlightPageById(id);
            if (page < 0) return -1;
            gotoPage(page, false);
            return page;
          }

          function apply() {
            if (!body) return;

            var viewportHeight = window.innerHeight || html.clientHeight || 1;
            // Patch 12: reserve TOP_GUARD px at the top (breathing room below the
            // status bar) AND GUARD px at the bottom (page-indicator space). The
            // body is pushed down by TOP_GUARD and its column height shrinks by
            // (TOP_GUARD + GUARD) so the top and bottom reserved spaces are equal
            // and symmetric. Both spaces are painted with the reading background
            // color (white/sepia/black), not page content.
            var height = Math.max(40, viewportHeight - TOP_GUARD - GUARD);

            // html spans 0..(viewportHeight - GUARD): the top TOP_GUARD of it is
            // empty (html background = reading bg), the body sits below it.
            html.style.setProperty('height', (height + TOP_GUARD) + 'px', 'important');
            // Push the column container down by TOP_GUARD so page content starts
            // below the status bar. !important is needed because the body CSS sets
            // `margin:0 !important`.
            body.style.setProperty('margin-top', TOP_GUARD + 'px', 'important');
            body.style.setProperty('height', height + 'px', 'important');

            body.style.setProperty('column-width', colW() + 'px', 'important');
            body.style.setProperty('-webkit-column-width', colW() + 'px', 'important');
            body.style.setProperty('column-count', 'auto', 'important');
            body.style.setProperty('-webkit-column-count', 'auto', 'important');
            body.style.setProperty('column-fill', 'auto', 'important');
            body.style.setProperty('-webkit-column-fill', 'auto', 'important');
          }

          function pageSnippet(page) {
            if (!body) return '';
            var target = Math.max(0, Math.floor(page || 0));
            var startX = target * advance();
            var endX = startX + advance();
            var walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT, null, false);
            var words = [];
            var total = 0;
            while (walker.nextNode() && total < 220) {
              var node = walker.currentNode;
              var value = node.textContent || '';
              if (!value.trim()) continue;
              var matches = value.match(/\S+/g) || [];
              var searchFrom = 0;
              for (var i = 0; i < matches.length && total < 220; i++) {
                var word = matches[i];
                var offset = value.indexOf(word, searchFrom);
                if (offset < 0) continue;
                searchFrom = offset + word.length;
                var range = document.createRange();
                range.setStart(node, offset);
                range.setEnd(node, offset + word.length);
                var rects = range.getClientRects();
                var visible = false;
                for (var j = 0; j < rects.length; j++) {
                  var rect = rects[j];
                  var cx = rect.left + (body.scrollLeft || 0) + rect.width / 2;
                  if (cx >= startX - 1 && cx < endX + 1 && rect.bottom > 0 && rect.top < (window.innerHeight || 1)) {
                    visible = true;
                    break;
                  }
                }
                range.detach();
                if (visible) {
                  var next = words.length ? (words.join(' ') + ' ' + word) : word;
                  if (next.length > 180) break;
                  words.push(word);
                  total = next.length;
                }
              }
            }
            return words.join(' ').replace(/\s+/g, ' ').trim().slice(0, 180);
          }

          function pageForTextAnchor(anchor, fallbackPage) {
            if (!anchor) return fallbackPage || 0;
            var wanted = String(anchor).replace(/\\s+/g,' ').trim().toLowerCase();
            if (!wanted) return fallbackPage || 0;
            var blocks = document.body.querySelectorAll('p,li,blockquote,h1,h2,h3,h4,h5,h6,div,td,th');
            var best = null, bestLen = Infinity;
            for (var i=0;i<blocks.length;i++) {
              var text = (blocks[i].textContent || '').replace(/\\s+/g,' ').trim();
              if (!text) continue;
              var lower = text.toLowerCase();
              if (lower.indexOf(wanted) >= 0 && text.length < bestLen) { best = blocks[i]; bestLen = text.length; }
            }
            if (!best) {
              for (var j=0;j<blocks.length;j++) {
                var candidate=(blocks[j].textContent||'').replace(/\\s+/g,' ').trim().toLowerCase();
                if (candidate && (candidate.indexOf(wanted.slice(0,Math.min(40,wanted.length)))>=0 || wanted.indexOf(candidate.slice(0,Math.min(40,candidate.length)))>=0)) { best=blocks[j]; break; }
              }
            }
            if (best) {
              var x=0, node=best;
              while(node){ x += node.offsetLeft || 0; node=node.offsetParent; }
              return Math.floor(x / advance());
            }
            return fallbackPage || 0;
          }

          function init() {
            body = document.body;

            if (!body) {
              setTimeout(init, 30);
              return;
            }

            apply();

            window.Caesura = {
              apply: apply,
              pageCount: pageCount,
              currentPage: currentPage,
              gotoPage: gotoPage,
              nextPage: nextPage,
              prevPage: prevPage,
              ratio: ratio,
              gotoElementById: gotoElementById,
              pageForElementById: pageForElementById,
              pageForTextAnchor: pageForTextAnchor,
              pageSnippet: pageSnippet,
              highlightPageById: highlightPageById,
              gotoHighlightById: gotoHighlightById
            };
          }

          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', init, { once: true });
          } else {
            init();
          }

          window.addEventListener('load', function () {
            if (window.Caesura) window.Caesura.apply();
          });

          window.addEventListener('resize', function () {
            if (window.Caesura) window.Caesura.apply();
          });
        })();
        </script>
    """.trimIndent()
    }

    private fun ColorToHex(c: Int): String {
        val a = android.graphics.Color.alpha(c)
        val r = android.graphics.Color.red(c)
        val g = android.graphics.Color.green(c)
        val b = android.graphics.Color.blue(c)
        return if (a < 255) String.format("#%08X", c) else String.format("#%06X", c and 0xFFFFFF)
    }

    // ---------------------------------------------------------------- navigation
    private fun navigateToUrl(url: String) {
        clearReaderSelection()
        val book = epub ?: return
        val path = url.substringAfter(EpubResourceResolver.VIRTUAL_HOST).trimStart('/')
        val parts = path.split("/", limit = 2)
        if (parts.size < 2) return
        val entryPath = parts[1].substringBefore('#').substringBefore('?')
        val frag = if ('#' in parts[1]) parts[1].substringAfter('#') else null
        val idx = book.spine.indexOfFirst { it.href == entryPath }
        if (idx < 0) return

        // Resolve the visible WebView page before navigation so every internal
        // destination (TOC and EPUB links included) follows the same history rule.
        binding.webView.evaluateJavascript(
            "(function(){if(!window.Caesura) return '';return window.Caesura.currentPage()+','+window.Caesura.ratio();})();"
        ) { result ->
            val values = result?.trim()?.removeSurrounding("\"")?.split(',')
            val actualPage = values?.getOrNull(0)?.toIntOrNull()
            val actualRatio = values?.getOrNull(1)?.toFloatOrNull()
            val current = captureReaderLocation()?.let { location ->
                if (actualPage != null && actualPage >= 0) {
                    location.copy(pageInChapter = actualPage, ratio = actualRatio ?: location.ratio)
                } else location
            }

            if (idx != spineIndex) {
                if (!restoringHistoryLocation && current != null) {
                    pushHistory(current)
                }
                pendingFragment = frag
                pendingTargetPageInChapter = null
                restoreRatio = null
                pendingHistoryCursorAfterRestore = true
                loadChapter(idx)
                return@evaluateJavascript
            }

            val targetExpression = if (frag != null) {
                val safe = frag.replace("'", "")
                "window.Caesura.pageForElementById('$safe')"
            } else {
                "0"
            }
            binding.webView.evaluateJavascript(
                "if(window.Caesura){window.Caesura.currentPage() + '|' + $targetExpression;}"
            ) { targetResult ->
                val targetValues = targetResult?.trim()?.removeSurrounding("\"")?.split('|')
                val currentPage = targetValues?.getOrNull(0)?.toIntOrNull() ?: actualPage
                val targetPage = targetValues?.getOrNull(1)?.toIntOrNull()

                if (targetPage == null || targetPage < 0) return@evaluateJavascript

                if (!restoringHistoryLocation && current != null &&
                    currentPage != null && targetPage != currentPage
                ) {
                    pushHistory(current.copy(pageInChapter = currentPage))
                }

                if (frag != null) {
                    pendingFragment = frag
                    pendingTargetPageInChapter = null
                    restoreRatio = null
                    pendingHistoryCursorAfterRestore = true
                    binding.webView.evaluateJavascript(
                        "if(window.Caesura){window.Caesura.gotoElementById('$frag');}"
                    ) {
                        binding.webView.evaluateJavascript(
                            "if(window.Caesura){window.Caesura.currentPage()+','+window.Caesura.ratio();}"
                        ) { targetLocationResult ->
                            val targetValues = targetLocationResult?.trim()?.removeSurrounding("\"")?.split(',')
                            val targetActualPage = targetValues?.getOrNull(0)?.toIntOrNull()
                            val targetActualRatio = targetValues?.getOrNull(1)?.toFloatOrNull()
                            if (pendingHistoryCursorAfterRestore && targetActualPage != null) {
                                historyCursorLocation = ReaderLocation(spineIndex, targetActualPage, targetActualRatio ?: 0f)
                                pendingHistoryCursorAfterRestore = false
                            }
                            handler.postDelayed({ pollProgress() }, 80L)
                        }
                    }
                } else {
                    pendingFragment = null
                    pendingTargetPageInChapter = targetPage
                    pendingHistoryCursorAfterRestore = true
                    capturePageSnapshot(forward = false)
                    applyPendingFragmentOrRestore()
                }
            }
        }
    }

    private fun goToSpine(index: Int) {
        clearReaderSelection()
        val book = epub ?: return
        if (index in book.spine.indices && index != spineIndex) {
            pendingFragment = null
            loadChapter(index)
        }
    }

    /** Switch the bottom timeline from chapter-level to per-page once every
     *  chapter has been measured. Until then the seeker stays chapter-level so a
     *  drag can never land on a chapter's first page by accident. */
    private fun enablePerPageSeeker() {
        val counts = chapterPageCounts ?: return
        if (counts.isEmpty() || counts.any { it < 0 }) return  // not ready yet
        val total = ReaderPageMapping.totalPages(counts)
        if (total <= 1) return
        perPageSeekerActive = true
        binding.seekChapter.max = total - 1
        syncSeekBarFromCurrentPage()
    }

    private fun syncSeekBarFromCurrentPage() {
        if (!perPageSeekerActive) return
        val counts = chapterPageCounts ?: return
        if (counts.isEmpty() || counts.any { it < 0 }) return
        // Seeker position = absolute synthetic book page (prefix + ratio-derived
        // page within the current spine), so it tracks reading position smoothly.
        val abs = currentAbsoluteBookPage()
        binding.seekChapter.progress = abs.coerceIn(0, binding.seekChapter.max)
    }

    /** Dragging the whole-book seeker: resolve the absolute rendered page to an
     *  exact (spine, page) pair and use the same Caesura pagination API that the
     *  existing tap/TOC navigation already uses. */
    private fun seekToAbsolutePage(absolute: Int) {
        val counts = chapterPageCounts ?: return
        if (counts.isEmpty() || counts.any { it < 0 }) return
        val (targetSpine, pageInSpine) = ReaderPageMapping.spineAndPageFor(counts, absolute)
        val pagesInSpine = counts[targetSpine].coerceAtLeast(1)
        val ratio = if (pagesInSpine > 1) pageInSpine / (pagesInSpine - 1).toFloat() else 0f
        if (targetSpine == spineIndex) {
            // Reuse the visible reader's real pagination. The target is already an
            // exact Caesura page index, so do not convert it through a ratio.
            capturePageSnapshot(forward = absolute >= currentAbsoluteBookPage())
            binding.webView.evaluateJavascript(
                "if(window.Caesura){window.Caesura.gotoPage(${pageInSpine.coerceAtLeast(0)},false);}"
            ) {
                currentPageInChapter = pageInSpine.coerceIn(0, pagesInSpine - 1)
                currentScrollRatio = ratio.coerceIn(0f, 1f)
                updateOverallProgress()
                updatePageIndicator()
                updateSectionPages()
                dismissPageSnapshot()
                handler.postDelayed({ pollProgress() }, 80L)
            }
        } else {
            // Carry the exact rendered page across the chapter load. Using only a
            // ratio here was the source of the old "chapter starts at page 1" drag
            // behavior when the target chapter had a different pagination geometry.
            pendingTargetPageInChapter = pageInSpine
            restoreRatio = ratio
            goToSpine(targetSpine)
        }
    }

    // ---------------------------------------------------------------- restore / flash
    /** Capture the currently visible page into the snapshot overlay so it stays
     *  on screen (above the reading WebView) while the next page/chapter loads and
     *  is positioned. Removed via [dismissPageSnapshot] once the target page is
     *  revealed. Best-effort: if capture fails, falls back to the previous
     *  alpha-hide behavior (no regression).
     *
     *  Patch 9: this is now used for EVERY page transition (in-chapter page turns,
     *  cross-chapter turns, TOC jumps, and seeker jumps), so it may be called in
     *  rapid succession. To avoid leaking a full-screen bitmap on every turn, the
     *  previous snapshot bitmap is recycled before the new one is installed.
     *
     *  Patch 17 (Addition #2): [forward] is remembered in [snapshotForward] so the
     *  slide-out direction is known at dismiss time even for paths that don't pass
     *  it explicitly (chapter restore, etc.). */
    private fun capturePageSnapshot(forward: Boolean = true) {
        snapshotForward = forward
        snapshotAnimToken++  // invalidate any in-flight dismiss end-action
        val view = binding.webView
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return
        val snapshot = binding.snapshotView
        // Stop any in-flight slide/fade so we don't fight it / draw a recycled bitmap.
        snapshot.animate().cancel()
        // Recycle the previously captured bitmap now that we're replacing it.
        val previous = (snapshot.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            previous?.recycle()
            return
        }
        try {
            val canvas = Canvas(bmp)
            view.draw(canvas)
            snapshot.setImageDrawable(null)
            snapshot.setImageBitmap(bmp)
            snapshot.alpha = 1f
            snapshot.translationX = 0f
            snapshot.visibility = View.VISIBLE
            // Recycle the old bitmap only after it's no longer the ImageView's drawable.
            previous?.recycle()
        } catch (_: Exception) {
            bmp.recycle()
            snapshot.visibility = View.GONE
        }
    }

    /** Remove the captured page snapshot. Patch 17 (Addition #2):
     *  - Page Turn Animation ON  -> slide the old page out horizontally in the
     *    turn direction (forward = off the left edge, back = off the right edge)
     *    with a short alpha fade, reading like turning a physical page. Duration
     *    [PAGE_TURN_DURATION_MS] (longer than the old 220ms crossfade).
     *  - Page Turn Animation OFF -> clear instantly (no animation).
     *  [forward] overrides the remembered direction; null = use [snapshotForward].
     *  A token ([snapshotAnimToken]) guards the end-action so a slide that gets
     *  cancelled by a newer capture can never recycle the newer bitmap. */
    private fun dismissPageSnapshot(forward: Boolean? = null) {
        val v = binding.snapshotView
        if (v.visibility != View.VISIBLE) return
        v.animate().cancel()  // avoid stacking animations / recycling a drawn bitmap
        v.alpha = 1f
        v.translationX = 0f
        val dir = forward ?: snapshotForward
        if (!prefs.pageTurnAnimation) {
            clearSnapshot(v)
            return
        }
        val w = v.width
        val targetX = if (dir) -w else w
        val myToken = ++snapshotAnimToken
        v.animate()
            .translationX(targetX.toFloat())
            .alpha(0f)   // ease the leading edge so it never hard-cuts
            .setDuration(PAGE_TURN_DURATION_MS)
            .withEndAction {
                // Only clean up if no newer capture replaced this snapshot meanwhile.
                if (myToken == snapshotAnimToken) clearSnapshot(v)
            }
            .start()
    }

    /** Detach + recycle the snapshot bitmap and reset the ImageView transform.
     *  Shared by the animated and instant dismiss paths so cleanup is identical. */
    private fun clearSnapshot(v: ImageView) {
        val drawable = v.drawable as? android.graphics.drawable.BitmapDrawable
        v.setImageDrawable(null)
        v.visibility = View.GONE
        v.translationX = 0f
        v.alpha = 1f
        drawable?.bitmap?.recycle()
    }

    private fun applyPendingFragmentOrRestore() {
        val frag = pendingFragment
        pendingFragment = null
        val targetPage = pendingTargetPageInChapter
        pendingTargetPageInChapter = null
        val reflowAnchor = pendingReflowAnchor
        pendingReflowAnchor = null
        if (reflowAnchor != null) {
            val safeText = org.json.JSONObject.quote(reflowAnchor.text)
            binding.webView.evaluateJavascript(
                "if(window.Caesura){window.Caesura.pageForTextAnchor($safeText,${reflowAnchor.fallbackPage});}"
            ) { result ->
                val page = result?.trim()?.removeSurrounding("\"")?.toIntOrNull()
                    ?: reflowAnchor.fallbackPage
                binding.webView.evaluateJavascript(
                    "if(window.Caesura){window.Caesura.gotoPage(${page.coerceAtLeast(0)},false);}"
                ) {
                    binding.webView.alpha = 1f
                    dismissPageSnapshot()
                    restoreRatio = null
                    restoringHistoryLocation = false
                    updateHistoryUi()
                    handler.post { pollProgress() }
                }
            }
        } else if (frag != null) {
            val safe = frag.replace("'", "")
            binding.webView.evaluateJavascript(
                "if(window.Caesura){window.Caesura.gotoElementById('$safe');}"
            ) {
                binding.webView.alpha = 1f
                dismissPageSnapshot()
                if (pendingHistoryCursorAfterRestore) {
                    binding.webView.evaluateJavascript(
                        "if(window.Caesura){window.Caesura.currentPage()+','+window.Caesura.ratio();}"
                    ) { result ->
                        val values = result?.trim()?.removeSurrounding("\"")?.split(',')
                        val page = values?.getOrNull(0)?.toIntOrNull()
                        val ratio = values?.getOrNull(1)?.toFloatOrNull()
                        if (page != null) {
                            historyCursorLocation = ReaderLocation(spineIndex, page, ratio ?: 0f)
                            pendingHistoryCursorAfterRestore = false
                        }
                    }
                }
                // Refresh indicator + seeker right away so they reflect the
                // restored page instead of waiting for the next 1.5s poll.
                restoringHistoryLocation = false
                updateHistoryUi()
                handler.post { pollProgress() }
            }
        } else if (targetPage != null) {
            binding.webView.evaluateJavascript(
                "if(window.Caesura){window.Caesura.gotoPage(${targetPage.coerceAtLeast(0)},false);}"
            ) {
                binding.webView.alpha = 1f
                dismissPageSnapshot()
                if (pendingHistoryCursorAfterRestore) {
                    historyCursorLocation = ReaderLocation(
                        spineIndex,
                        targetPage.coerceAtLeast(0),
                        if (pagesInChapter > 1) targetPage.coerceAtLeast(0) / (pagesInChapter - 1).toFloat() else 0f
                    )
                    pendingHistoryCursorAfterRestore = false
                }
                restoreRatio = null
                restoringHistoryLocation = false
                updateHistoryUi()
                handler.post { pollProgress() }
            }
        } else {
            val ratio = restoreRatio ?: currentScrollRatio.takeIf { it > 0f }
            restoreRatio = null
            if (ratio != null && ratio > 0f) {
                binding.webView.evaluateJavascript(
                    "if(window.Caesura){var pc=window.Caesura.pageCount();window.Caesura.gotoPage(Math.round(($ratio)*Math.max(0,pc-1)),false);}"
                ) {
                    binding.webView.alpha = 1f
                    dismissPageSnapshot()
                    if (pendingHistoryCursorAfterRestore) {
                        binding.webView.evaluateJavascript(
                            "if(window.Caesura){window.Caesura.currentPage()+','+window.Caesura.ratio();}"
                        ) { result ->
                            val values = result?.trim()?.removeSurrounding("\"")?.split(',')
                            val page = values?.getOrNull(0)?.toIntOrNull()
                            val ratioValue = values?.getOrNull(1)?.toFloatOrNull()
                            if (page != null) {
                                historyCursorLocation = ReaderLocation(spineIndex, page, ratioValue ?: 0f)
                            }
                            pendingHistoryCursorAfterRestore = false
                        }
                    }
                    restoringHistoryLocation = false
                    updateHistoryUi()
                    handler.post { pollProgress() }
                }
            } else {
                binding.webView.alpha = 1f
                dismissPageSnapshot()
                // A cross-chapter explicit navigation without a fragment or
                // exact page still needs to establish the history cursor.
                // Otherwise Back can fall back to the previous cursor and
                // incorrectly create a Forward entry for the page we just left.
                if (pendingHistoryCursorAfterRestore) {
                    binding.webView.evaluateJavascript(
                        "if(window.Caesura){window.Caesura.currentPage()+','+window.Caesura.ratio();}"
                    ) { result ->
                        val values = result?.trim()?.removeSurrounding("\"")?.split(',')
                        val page = values?.getOrNull(0)?.toIntOrNull()
                        val ratioValue = values?.getOrNull(1)?.toFloatOrNull()
                        if (page != null) {
                            historyCursorLocation = ReaderLocation(spineIndex, page, ratioValue ?: 0f)
                        }
                        pendingHistoryCursorAfterRestore = false
                    }
                }
                restoringHistoryLocation = false
                updateHistoryUi()
                handler.post { pollProgress() }
            }
        }
    }

    // ---------------------------------------------------------------- progress + indicators
    private fun pollProgress() {

        if (overlayVisible()) return
        if (userSeeking) return

        val requestToken =
            ++progressRequestToken

        binding.webView.evaluateJavascript(
            "(function(){if(!window.Caesura) return '';return window.Caesura.currentPage()+','+window.Caesura.pageCount()+','+window.Caesura.ratio();})();"
        ) { result ->

            // An older asynchronous WebView response must never overwrite
            // a newer seek/navigation result.
            if (requestToken != progressRequestToken) {
                return@evaluateJavascript
            }

            if (userSeeking) {
                return@evaluateJavascript
            }

            parseProgress(result)
        }
    }

    private fun parseProgress(result: String?) {
        if (result == null || result == "null" || result.isBlank()) return
        try {
            val parts = result.trim('\"').split(",")
            if (parts.size != 3) return
            val reportedPage = parts[0].toIntOrNull() ?: 0
            val reportedCount = parts[1].toIntOrNull()?.coerceAtLeast(1) ?: 1

            // Do not let a poll that was queued before a seeker tap/drag briefly
            // paint the old page number over the exact page the user just chose.
            // We clear the guard only when the visible WebView reports the same
            // page back, which makes the correction deterministic instead of
            // relying on a timing delay.
            pendingExactSeekLocation?.let { expected ->
                if (expected.spineIndex == spineIndex && reportedPage == expected.pageInChapter) {
                    pendingExactSeekLocation = null
                    exactSeekUiLocation = null
                } else {
                    return
                }
            }

            currentPageInChapter = reportedPage
            pagesInChapter = reportedCount
            // The visible WebView already knows the real rendered page count for
            // the chapter immediately. Reuse that information instead of making
            // the background measurement pass rediscover the current chapter.
            chapterPageCounts?.let { counts ->
                if (spineIndex in counts.indices) counts[spineIndex] = pagesInChapter
            }
            val r = parts[2].toFloatOrNull() ?: return
            currentScrollRatio = r.coerceIn(0f, 1f)
            updateOverallProgress()
            updatePageIndicator()
            updateSectionPages()
            syncSeekBarFromCurrentPage()
        } catch (_: Exception) {
        }
    }

    private fun updateOverallProgress() {
        val book = epub ?: return
        if (book.spine.isEmpty()) return
        // Keep the original reader progress/page model. Only Room persistence is
        // debounced so WebView polling does not write on every callback.
        val counts = chapterPageCounts
        val progress = if (counts != null && counts.isNotEmpty() && counts.none { it < 0 }) {
            val total = ReaderPageMapping.totalPages(counts)
            val absolute = exactSeekUiLocation?.let { target ->
                val prefix = ReaderPageMapping.prefixSums(counts)
                (prefix.getOrNull(target.spineIndex) ?: 0) + target.pageInChapter
            } ?: currentAbsoluteBookPage()
            if (total <= 1) 1f else
                (absolute.coerceIn(0, total - 1) / (total - 1).toFloat()).coerceIn(0f, 1f)
        } else {
            ((spineIndex + currentScrollRatio) / book.spine.size).toFloat().coerceIn(0f, 1f)
        }
        binding.tvPercent.text = "${(progress * 100).toInt()}%"
        pendingProgressValue = progress
        pendingProgressSpine = spineIndex
        pendingProgressRatio = currentScrollRatio

        val now = System.currentTimeMillis()
        val materiallyChanged =
            lastPersistedProgress == null ||
                    kotlin.math.abs(progress - (lastPersistedProgress ?: 0f)) >= 0.001f ||
                    spineIndex != lastPersistedSpine ||
                    kotlin.math.abs(currentScrollRatio - lastPersistedRatio) >= 0.01f

        if (materiallyChanged || now - lastProgressPersistAt >= 5000L) {
            handler.removeCallbacks(persistProgressRunnable)
            handler.postDelayed(persistProgressRunnable, 1200L)
        }
    }

    private fun persistProgressNow() {
        if (bookId < 0L) return
        val progress = pendingProgressValue
        val spine = pendingProgressSpine
        val ratio = pendingProgressRatio
        val now = System.currentTimeMillis()
        lastPersistedProgress = progress
        lastPersistedSpine = spine
        lastPersistedRatio = ratio
        lastProgressPersistAt = now
        lifecycleScope.launch(Dispatchers.IO) {
            db.bookDao().updateProgress(bookId, progress, spine, ratio, now)
        }
    }

    /** Whole-book current page / total page indicator (persistent + bottom-bar bold). */
    private fun updatePageIndicator() {
        val counts = chapterPageCounts
        if (counts == null || counts.isEmpty() || counts.any { it < 0 }) {
            // Patch 7 behavior: the offscreen measurement pass is still running,
            // so the exact total isn't known yet. Show an ellipsis (the familiar
            // "…" flash) until every chapter has been measured, then the real
            // "page X / Y" replaces it.
            setPageText("…")
            return
        }
        val total = ReaderPageMapping.totalPages(counts)
        val displayAbsolute = exactSeekUiLocation?.let { target ->
            if (target.spineIndex in counts.indices) {
                val prefix = ReaderPageMapping.prefixSums(counts)
                (prefix.getOrNull(target.spineIndex) ?: 0) +
                    target.pageInChapter.coerceIn(0, counts[target.spineIndex].coerceAtLeast(1) - 1)
            } else null
        } ?: currentAbsoluteBookPage()
        val currentBookPage = (displayAbsolute + 1).coerceIn(1, total.coerceAtLeast(1))
        setPageText(getString(R.string.reader_page_of_pages, currentBookPage, total))
    }

    private fun setPageText(text: String) {
        binding.tvPageIndicator.text = text
        binding.tvPageInfo.text = text
    }

    /** Current rendered page within the current spine, using the page count that
     *  the visible Caesura WebView reports for the active chapter. */
    private fun currentAbsoluteBookPage(): Int {
        val counts = chapterPageCounts ?: return 0
        if (counts.isEmpty() || spineIndex !in counts.indices) return 0
        val prefix = ReaderPageMapping.prefixSums(counts)
        val pageCount = counts[spineIndex].coerceAtLeast(1)
        val page = currentPageInChapter.coerceIn(0, pageCount - 1)
        return (prefix.getOrNull(spineIndex) ?: 0) + page
    }

    /**
     * Spine index range of the current TOC section: from the nearest preceding
     * embedded-nav TOC entry up to (but not including) the next TOC entry.
     * A TOC section can span several spine items, so the top-bar page count must
     * aggregate measured page counts across the whole section, not just the
     * current spine item.
     */
    private fun sectionSpineRange(): IntRange {
        val book = epub ?: return 0..0
        val start = tocSections.lastOrNull { it.first <= spineIndex }?.first ?: 0
        val nextStart = tocSections.firstOrNull { it.first > start }?.first ?: book.spine.size
        val endExclusive = nextStart.coerceAtLeast(start + 1)
        return start until endExclusive
    }

    /** Top-bar row 4: section label + page X/Y within that section. */
    private fun updateSectionPages() {
        val label = sectionLabel()
        val counts = chapterPageCounts
        val text = if (counts == null || counts.isEmpty() || counts.any { it < 0 }) {
            // Counts not ready: fall back to the current chapter only.
            val cur = (currentPageInChapter + 1).coerceAtLeast(1)
            "$label - $cur/$pagesInChapter"
        } else {
            val range = sectionSpineRange()
            val total = range.sumOf { counts.getOrNull(it)?.coerceAtLeast(0) ?: 0 }.coerceAtLeast(1)
            // Prior section pages + the exact rendered page reported by the
            // visible WebView for the current spine item.
            val prior = (range.first until spineIndex).sumOf { counts.getOrNull(it)?.coerceAtLeast(0) ?: 0 }
            val current = currentPageInChapter.coerceIn(0, counts.getOrNull(spineIndex)?.coerceAtLeast(1)?.minus(1) ?: 0)
            val cur = (prior + current + 1).coerceIn(1, total)
            "$label - $cur/$total"
        }
        binding.tvSectionPages.text = text
    }

    // ---------------------------------------------------------------- TOC + Bookmarks overlay
    private var tocRv: RecyclerView? = null
    private var tocEmpty: TextView? = null
    private var bookmarkRv: RecyclerView? = null
    private var bookmarkEmpty: TextView? = null
    private var bookmarkAdapter: BookmarkAdapter? = null
    private var bookmarkObserverStarted = false
    private var highlightRv: RecyclerView? = null
    private var highlightEmpty: TextView? = null
    private var highlightObserverStarted = false
    private var pendingHighlightId: Long? = null
    private var pendingHighlightHistoryLocation: ReaderLocation? = null
    private var activeOverlayTab: Int = 0

    /** Whether the Bookmarks tab is currently shown in the TOC overlay. The
     *  bookmark DB observer fires refreshBookmarkList asynchronously; without
     *  this guard it re-shows the "empty bookmarks" hint over the TOC tab. */
    private var bookmarksTabActive = false

    private fun showTocBookmarks(selectBookmarks: Boolean = false, selectHighlights: Boolean = false) {
        clearReaderSelection()
        val book = epub ?: return
        val root = binding.overlayContent
        if (tocRv == null) {
            val tocView = LayoutInflater.from(this).inflate(R.layout.overlay_list, root, false)
            tocRv = tocView.findViewById(R.id.recycler)
            tocEmpty = tocView.findViewById(R.id.emptyText)
            tocRv!!.layoutManager = LinearLayoutManager(this)
            tocRv!!.adapter = TocAdapter { entry ->
                navigateToUrl("https://${EpubResourceResolver.VIRTUAL_HOST}/$bookId/${entry.href.trimStart('/')}")
                hideOverlays()
            }
            root.addView(tocView)

            val bmView = LayoutInflater.from(this).inflate(R.layout.overlay_list, root, false)
            bookmarkRv = bmView.findViewById(R.id.recycler)
            bookmarkEmpty = bmView.findViewById(R.id.emptyText)
            bookmarkRv!!.layoutManager = LinearLayoutManager(this)
            bookmarkAdapter = BookmarkAdapter(
                onDelete = { bookmark ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.bookmarkDao().delete(bookmark)
                        withContext(Dispatchers.Main) {
                            Snackbar
                                .make(binding.root, R.string.bookmark_deleted, Snackbar.LENGTH_LONG)
                                .setAction(R.string.undo) {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        db.bookmarkDao().insert(bookmark)
                                    }
                                }
                                .show()
                        }
                    }
                }
            ) { b -> goToBookmark(b); hideOverlays() }
            bookmarkRv!!.adapter = bookmarkAdapter
            root.addView(bmView)

            // Highlights list
            val hlView = LayoutInflater.from(this).inflate(R.layout.overlay_list, root, false)
            highlightRv = hlView.findViewById(R.id.recycler)
            highlightEmpty = hlView.findViewById(R.id.emptyText)
            highlightRv!!.layoutManager = LinearLayoutManager(this)
            highlightRv!!.adapter = HighlightListAdapter(
                onClick = { h ->
                    goToHighlight(h)
                    hideOverlays()
                },
                onDelete = { h ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.highlightDao().delete(h)
                        withContext(Dispatchers.Main) {
                            // Also unwrap the mark from the current page if visible.
                            binding.webView.evaluateJavascript(
                                "(function(){var m=document.querySelector('mark.livre-highlight[data-highlight-id=\"" + h.id + "\"]');if(m){var p=m.parentNode;while(m.firstChild)p.insertBefore(m.firstChild,m);p.removeChild(m);}})();",
                                null,
                            )
                            Snackbar
                                .make(binding.root, R.string.highlight_deleted, Snackbar.LENGTH_LONG)
                                .setAction(R.string.undo) {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        db.highlightDao().insert(h)
                                        withContext(Dispatchers.Main) {
                                            val currentHref = epub?.spine?.getOrNull(spineIndex)?.href
                                            if (currentHref == h.spineHref) {
                                                injectHighlightIntoWebView(h.id, h.text, h.prefix, h.suffix, h.color, h.startPath, h.endPath)
                                            }
                                        }
                                    }
                                }
                                .show()
                        }
                    }
                },
            )
            root.addView(hlView)
        }
        val tocAdapter =
            tocRv!!.adapter as TocAdapter

        tocAdapter.submitList(book.toc) {

            highlightCurrentTocEntry()
        }

        tocEmpty!!.visibility =
            if (book.toc.isEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        tocEmpty!!.text = getString(R.string.no_toc)
        bookmarkEmpty!!.text = getString(R.string.reader_bookmarks_empty)
        refreshBookmarkList()

        if (!bookmarkObserverStarted) {
            bookmarkObserverStarted = true
            db.bookmarkDao().observeForBook(bookId).asLiveData().observe(this) { list ->
                (bookmarkRv?.adapter as? BookmarkAdapter)?.submitList(list)
                refreshBookmarkList()
            }
        }
        if (!highlightObserverStarted) {
            highlightObserverStarted = true
            db.highlightDao().observeForBook(bookId).asLiveData().observe(this) { list ->
                (highlightRv?.adapter as? HighlightListAdapter)?.submitList(list)
                if (activeOverlayTab == binding.btnTabHighlights.id) {
                    highlightEmpty?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
        highlightEmpty?.text = getString(R.string.reader_highlights_empty)

        val initialTab = when {
            selectHighlights -> binding.btnTabHighlights.id
            selectBookmarks -> binding.btnTabBookmarks.id
            else -> binding.btnTabContents.id
        }
        binding.overlayTabGroup.check(initialTab)
        applyOverlayTab(initialTab)

        chromeVisible = false
        binding.topBar.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.tvPageIndicator.visibility = View.GONE
        binding.tocBookmarkOverlay.visibility = View.VISIBLE
        updateHistoryUi()
    }

    private fun refreshBookmarkList() {
        val list = (bookmarkRv?.adapter as? BookmarkAdapter)?.currentList ?: return
        // Only toggle the bookmark empty-hint while the Bookmarks tab is active —
        // otherwise an async DB update would surface it over the TOC tab.
        if (bookmarksTabActive) {
            bookmarkEmpty?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun applyOverlayTab(checkedId: Int) {
        activeOverlayTab = checkedId
        val isBookmarks = checkedId == binding.btnTabBookmarks.id
        val isHighlights = checkedId == binding.btnTabHighlights.id
        bookmarksTabActive = isBookmarks
        val tocEmpty = epub?.toc.isNullOrEmpty()
        if (isBookmarks) {
            tocRv?.visibility = View.GONE
            this.tocEmpty?.visibility = View.GONE
            highlightRv?.visibility = View.GONE
            highlightEmpty?.visibility = View.GONE
            bookmarkRv?.visibility = View.VISIBLE
            val bmEmpty = (bookmarkAdapter?.currentList?.isEmpty() != false)
            this.bookmarkEmpty?.visibility = if (bmEmpty) View.VISIBLE else View.GONE
        } else if (isHighlights) {
            tocRv?.visibility = View.GONE
            this.tocEmpty?.visibility = View.GONE
            bookmarkRv?.visibility = View.GONE
            this.bookmarkEmpty?.visibility = View.GONE
            highlightRv?.visibility = View.VISIBLE
            val hlEmpty = ((highlightRv?.adapter as? HighlightListAdapter)?.currentList?.isEmpty() != false)
            highlightEmpty?.visibility = if (hlEmpty) View.VISIBLE else View.GONE
        } else {
            bookmarkRv?.visibility = View.GONE
            this.bookmarkEmpty?.visibility = View.GONE
            highlightRv?.visibility = View.GONE
            highlightEmpty?.visibility = View.GONE
            tocRv?.visibility = View.VISIBLE
            this.tocEmpty?.visibility = if (tocEmpty) View.VISIBLE else View.GONE
        }
    }

    // ---------------------------------------------------------------- search overlay
    private var searchRv: RecyclerView? = null
    private var searchEmpty: TextView? = null
    private var searchAdapter: SearchResultAdapter? = null
    private var searchEdit: android.widget.EditText? = null

    private fun showSearchOverlay() {
        clearReaderSelection()
        if (searchRv == null) {
            val v = LayoutInflater.from(this).inflate(R.layout.overlay_list, binding.searchContent, false)
            searchRv = v.findViewById(R.id.recycler)
            searchEmpty = v.findViewById(R.id.emptyText)
            searchRv!!.layoutManager = LinearLayoutManager(this)
            searchAdapter = SearchResultAdapter { r ->
                epub?.let { book ->
                    navigateToUrl("https://${EpubResourceResolver.VIRTUAL_HOST}/$bookId/${book.spine[r.chapterIndex].href}")
                    hideOverlays()
                    binding.webView.postDelayed({ binding.webView.findAllAsync(searchEdit?.text.toString()) }, 400)
                }
            }
            searchRv!!.adapter = searchAdapter
            binding.searchContent.addView(v)
        }
        searchEmpty?.text = getString(R.string.reader_search_empty)
        searchEmpty?.visibility = View.VISIBLE
        searchAdapter?.submitList(emptyList())
        searchEdit = binding.searchEdit
        searchEdit?.setOnEditorActionListener { _, _, _ ->
            val q = searchEdit?.text.toString().orEmpty()
            if (q.isNotBlank()) performSearch(q) else {
                searchAdapter?.submitList(emptyList()); searchEmpty?.visibility = View.VISIBLE
            }
            true
        }
        chromeVisible = false
        binding.topBar.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.tvPageIndicator.visibility = View.GONE
        binding.searchOverlay.visibility = View.VISIBLE
        updateHistoryUi()
        searchEdit?.setText("")
        // Show the on-screen keyboard + place the cursor, mirroring how the
        // main app's SearchActivity opens search (the user does not have to
        // tap the field a second time to bring up the IME). Posted after the
        // overlay is visible so the IME reliably attaches to the field.
        binding.searchEdit.post {
            if (binding.searchOverlay.visibility == View.VISIBLE) {
                binding.searchEdit.requestFocus()
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(binding.searchEdit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun performSearch(query: String) {
        val book = epub ?: return
        val file = bookEntity?.path?.let { File(it) } ?: return
        searchEmpty?.visibility = View.GONE
        lifecycleScope.launch(Dispatchers.IO) {
            val engine = EpubSearchEngine(file)
            val results = engine.search(book.spine, tocMap(), query)
            withContext(Dispatchers.Main) {
                (searchRv?.adapter as? SearchResultAdapter)?.submitList(results)
                searchEmpty?.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun tocMap(): Map<String, String> {
        val book = epub ?: return emptyMap()
        val map = HashMap<String, String>()
        for (e in book.toc) {
            val key = e.href.substringBefore('#')
            if (key.isNotBlank() && !map.containsKey(key)) map[key] = e.label
        }
        return map
    }

    // ---------------------------------------------------------------- bookmarks
    private fun goToBookmark(b: BookmarkEntity) {
        clearReaderSelection()
        val book = epub ?: return
        if (b.spineIndex !in book.spine.indices) return

        binding.webView.evaluateJavascript(
            "(function(){if(!window.Caesura) return '';return window.Caesura.currentPage()+','+window.Caesura.pageCount()+','+window.Caesura.ratio();})();"
        ) { result ->
            val values = result?.trim()?.removeSurrounding("\"")?.split(',')
            val actualPage = values?.getOrNull(0)?.toIntOrNull()
            val pageCount = values?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1)
            val actualRatio = values?.getOrNull(2)?.toFloatOrNull()
            val current = captureReaderLocation()?.let { location ->
                if (actualPage != null && actualPage >= 0) {
                    location.copy(pageInChapter = actualPage, ratio = actualRatio ?: location.ratio)
                } else location
            }

            if (b.spineIndex != spineIndex) {
                if (!restoringHistoryLocation && current != null) {
                    pushHistory(current)
                }
                pendingFragment = null
                pendingTargetPageInChapter = null
                restoreRatio = b.scrollRatio
                pendingHistoryCursorAfterRestore = true
                loadChapter(b.spineIndex)
                return@evaluateJavascript
            }

            val count = pageCount ?: pagesInChapter.coerceAtLeast(1)
            val targetPage = if (b.pageInChapter >= 0) {
                b.pageInChapter.coerceIn(0, count - 1)
            } else if (count > 1) {
                kotlin.math.round(b.scrollRatio.coerceIn(0f, 1f) * (count - 1)).toInt()
            } else 0

            if (!restoringHistoryLocation && current != null &&
                actualPage != null && targetPage != actualPage
            ) {
                pushHistory(current.copy(pageInChapter = actualPage))
            }

            pendingFragment = null
            pendingTargetPageInChapter = targetPage
            restoreRatio = null
            pendingHistoryCursorAfterRestore = true
            capturePageSnapshot(forward = false)
            applyPendingFragmentOrRestore()
        }
    }

    private fun addBookmark() {
        val idx = spineIndex
        val title = sectionLabel()
        binding.webView.evaluateJavascript(
            "(function(){if(!window.Caesura) return '';var p=window.Caesura.currentPage();var r=window.Caesura.ratio();var t=window.Caesura.pageSnippet(p);return JSON.stringify({page:p,ratio:r,snippet:t});})();"
        ) { result ->
            val raw = runCatching { org.json.JSONTokener(result?.trim().orEmpty()).nextValue() as? String }.getOrNull() ?: ""
            val json = runCatching { org.json.JSONObject(raw) }.getOrNull()
            val page = json?.optInt("page", currentPageInChapter)?.coerceAtLeast(0) ?: currentPageInChapter
            val ratio = json?.optDouble("ratio", currentScrollRatio.toDouble())?.toFloat()?.coerceIn(0f, 1f) ?: currentScrollRatio
            val snippet = json?.optString("snippet").orEmpty().trim()
            lifecycleScope.launch(Dispatchers.IO) {
                val existing = db.bookmarkDao().findWholePage(bookId, idx, page, ratio)
                if (existing != null) {
                    withContext(Dispatchers.Main) {
                        Snackbar
                            .make(binding.root, R.string.bookmark_exists, Snackbar.LENGTH_LONG)
                            .setAction(R.string.delete) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    db.bookmarkDao().delete(existing)
                                    withContext(Dispatchers.Main) {
                                        Snackbar.make(binding.root, R.string.bookmark_deleted, Snackbar.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .show()
                    }
                    return@launch
                }
                db.bookmarkDao().insert(
                    BookmarkEntity(
                        bookId = bookId,
                        spineIndex = idx,
                        scrollRatio = ratio,
                        pageInChapter = page,
                        chapterTitle = title,
                        snippet = snippet.ifBlank { title },
                        bookmarkType = BookmarkEntity.TYPE_WHOLE_PAGE,
                    )
                )
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root, R.string.bookmark_added, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun addBookmarkFromSelection(selection: ReaderSelectionLocator) {
        val text = selection.text.trim()
        if (text.isBlank()) return
        val href = selection.spineHref
        val idx = epub?.spine?.indexOfFirst { it.href == href }?.takeIf { it >= 0 } ?: spineIndex
        val title = sectionLabel()
        binding.webView.evaluateJavascript(
            "if(window.Caesura){window.Caesura.currentPage()+','+window.Caesura.ratio();}"
        ) { result ->
            val values = result?.trim()?.removeSurrounding("\"")?.split(',')
            val page = values?.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(0) ?: currentPageInChapter
            val ratio = values?.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: currentScrollRatio
            lifecycleScope.launch(Dispatchers.IO) {
                val existing = db.bookmarkDao().findText(bookId, idx, page, text)
                if (existing != null) {
                    withContext(Dispatchers.Main) {
                        Snackbar
                            .make(binding.root, R.string.bookmark_exists, Snackbar.LENGTH_LONG)
                            .setAction(R.string.delete) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    db.bookmarkDao().delete(existing)
                                    withContext(Dispatchers.Main) {
                                        Snackbar.make(binding.root, R.string.bookmark_deleted, Snackbar.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .show()
                    }
                    return@launch
                }
                db.bookmarkDao().insert(
                    BookmarkEntity(
                        bookId = bookId,
                        spineIndex = idx,
                        scrollRatio = ratio,
                        pageInChapter = page,
                        chapterTitle = title,
                        snippet = text,
                        bookmarkType = BookmarkEntity.TYPE_TEXT,
                    )
                )
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root, R.string.bookmark_added, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------------------------------------------------------------- overlay helpers / back
    private fun overlayVisible(): Boolean =
        binding.tocBookmarkOverlay.visibility == View.VISIBLE || binding.searchOverlay.visibility == View.VISIBLE

    private fun hideOverlays() {
        binding.tocBookmarkOverlay.visibility = View.GONE
        binding.searchOverlay.visibility = View.GONE
        bookmarksTabActive = false
        binding.tvPageIndicator.visibility = View.VISIBLE
        updateHistoryUi()
        binding.webView.requestFocus()
    }

    private fun setupOverlays() {
        binding.btnOverlayBack.setOnClickListener { hideOverlays() }
        binding.btnSearchBack.setOnClickListener {
            // Mirror the main app's search back behavior: closing the overlay
            // also dismisses the IME so the user doesn't have to reach for the
            // nav-bar back button to hide the keyboard.
            binding.searchEdit.clearFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.searchEdit.windowToken, 0)
            hideOverlays()
        }
        binding.overlayTabGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked && checkedId != View.NO_ID) applyOverlayTab(checkedId)
        }
        // Capture taps so they don't fall through to the WebView.
        binding.tocBookmarkOverlay.setOnClickListener { }
        binding.searchOverlay.setOnClickListener { }
    }

    @Deprecated("Use the OnBackPressedDispatcher.", ReplaceWith("onBackPressedDispatcher.onBackPressed()"))
    override fun onBackPressed() {
        if (binding.searchOverlay.visibility == View.VISIBLE) {
            hideOverlays(); return
        }
        if (binding.tocBookmarkOverlay.visibility == View.VISIBLE) {
            hideOverlays(); return
        }
        // Patch v37: if the Read Aloud overlay is open, back minimizes it
        // (playback keeps going — Stop is the button that ends read-aloud).
        if (binding.ttsOverlay.visibility == View.VISIBLE) {
            hideTtsOverlay(); return
        }
        if (chromeVisible) {
            toggleChrome(); return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---------------------------------------------------------------- settings
    /** Patch 17 (Issue #1): reader settings are now a full-screen Activity
     *  (ReaderSettingsActivity), not a BottomSheet. It writes changes to prefs as
     *  the user makes them and returns RESULT_OK if anything changed; we apply
     *  them ONCE here on return (window theme + chapter reload + re-measure).
     *  This avoids re-measuring page counts on every +/- tap. */
    private fun showSettings() {
        clearReaderSelection()
        settingsLauncher.launch(Intent(this, ReaderSettingsActivity::class.java))
    }

    private val settingsLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                captureReflowAnchor { anchor ->
                    pendingReflowAnchor = anchor
                    applySettingsAndReload()
                }
            }
        }

    private fun captureReflowAnchor(onCaptured: (ReflowAnchor?) -> Unit) {
        val fallbackPage = currentPageInChapter
        binding.webView.evaluateJavascript(
            """(function(){
                if(!window.Caesura) return '';
                var x=(window.innerWidth||1)*0.25, y=(window.innerHeight||1)*0.5;
                var range=null;
                if(document.caretRangeFromPoint) range=document.caretRangeFromPoint(x,y);
                else if(document.caretPositionFromPoint){
                    var pos=document.caretPositionFromPoint(x,y);
                    if(pos){range=document.createRange();range.setStart(pos.offsetNode,pos.offset);range.collapse(true);}
                }
                if(!range) return JSON.stringify({text:'',page:window.Caesura.currentPage()});
                var node=range.startContainer;
                if(node&&node.nodeType!==3) node=node.firstChild;
                var block=node&&node.parentElement?node.parentElement:null;
                while(block&&block!==document.body&&!/^(P|LI|BLOCKQUOTE|H1|H2|H3|H4|H5|H6|DIV|TD|TH)$/i.test(block.tagName)) block=block.parentElement;
                if(!block) return JSON.stringify({text:'',page:window.Caesura.currentPage()});
                var walker=document.createTreeWalker(block,NodeFilter.SHOW_TEXT,null,false), offset=0, found=false;
                while(walker.nextNode()){
                    if(walker.currentNode===range.startContainer){offset+=range.startOffset;found=true;break;}
                    offset+=walker.currentNode.textContent.length;
                }
                var text=(block.textContent||'').replace(/\\s+/g,' ').trim();
                if(!found||!text) return JSON.stringify({text:'',page:window.Caesura.currentPage()});
                var left=Math.max(0,offset-55), right=Math.min((block.textContent||'').length,offset+65);
                var anchor=(block.textContent||'').slice(left,right).replace(/\\s+/g,' ').trim();
                return JSON.stringify({text:anchor,page:window.Caesura.currentPage()});
            })();""",
        ) { result ->
            val raw = runCatching { org.json.JSONTokener(result?.trim().orEmpty()).nextValue() as? String }.getOrNull()
            val json = runCatching { raw?.let { org.json.JSONObject(it) } }.getOrNull()
            val text = json?.optString("text").orEmpty()
            val page = json?.optInt("page", fallbackPage) ?: fallbackPage
            onCaptured(text.takeIf { it.isNotBlank() }?.let { ReflowAnchor(it, page.coerceAtLeast(0)) })
        }
    }

    private fun applySettingsAndReload() {
        applyWindowTheme()

        // Patch 7 behavior: reader settings (font family / size / line height /
        // margins / alignment) change the layout, so the per-chapter page counts
        // must be RE-MEASURED — the total can change when you bump the font size,
        // exactly like Calibre / Readium. Cancel any in-flight measurement, reset
        // every chapter to "not measured yet," drop the per-page seeker back to
        // chapter-level, reload the current chapter, and restart the offscreen
        // measurement pass for the new layout.
        cancelMeasurement()
        val spineSize = epub?.spine?.size ?: 0
        chapterPageCounts = IntArray(spineSize) { -1 }
        perPageSeekerActive = false
        if (spineSize > 0) {
            binding.seekChapter.max = (spineSize - 1).coerceAtLeast(0)
            binding.seekChapter.progress = spineIndex.coerceIn(0, spineSize - 1)
        }
        restoreRatio = if (pendingReflowAnchor == null) currentScrollRatio else null
        loadChapter(spineIndex, resetRatio = false)
        binding.webView.post {
            val entity = bookEntity
            val cached = entity != null && loadCachedScreenPageCounts(entity)
            if (!cached) {
                startMeasurement()
            }
            updatePageIndicator()
            updateSectionPages()
        }
        updatePageIndicator()
        updateSectionPages()
    }

    // ---------------------------------------------------------------- lifecycle
    override fun onPause() {
        recordReadingSession(System.currentTimeMillis())
        // Patch v37: keep read-aloud running when the user has enabled
        // background playback; otherwise preserve the old stop-on-pause
        // behavior.
        if (!prefs.ttsBackgroundPlayback) {
            ttsController?.stop()
            hideTtsOverlay()
        } else {
            updateTtsServiceState()
        }
        super.onPause()
        handler.removeCallbacks(progressPoller)
        handler.removeCallbacks(alphaFallback)
        updateOverallProgress()
        handler.removeCallbacks(persistProgressRunnable)
        persistProgressNow()
        keepScreenOnController.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(persistProgressRunnable)
        persistProgressNow()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (epub != null) beginReadingSession(System.currentTimeMillis())
        handler.post(progressPoller)
        updatePageIndicator()
        updateSectionPages()
        keepScreenOnController.onResume()
        // Patch v37: if a modal overlay (TOC / Bookmarks / Search) was open when
        // the activity was paused (e.g. phone closed), the system may restore
        // window visibility on resume. Force the reader chrome hidden so the
        // top/bottom bars don't appear alongside the overlay.
        if (overlayVisible()) {
            chromeVisible = false
            binding.topBar.visibility = View.GONE
            binding.bottomBar.visibility = View.GONE
            binding.tvPageIndicator.visibility = View.GONE
        }
        // Same for the TTS panel: if it's visible, keep the bottom bar hidden.
        if (binding.ttsOverlay.visibility == View.VISIBLE) {
            binding.bottomBar.visibility = View.GONE
        }
    }

    override fun onUserInteraction() {
        val now = System.currentTimeMillis()
        val previous = readingSessionLastInteractionAt
        if (readingSessionStartedAt != null && previous > 0L) {
            val gap = ((now - previous) / 1000L).coerceAtLeast(0L)
            if (gap <= SESSION_IDLE_GAP_SECONDS) readingSessionActiveSeconds += gap.toInt()
        }
        readingSessionLastInteractionAt = now
        super.onUserInteraction()
        keepScreenOnController.bump()
    }

    override fun onDestroy() {
        cancelMeasurement()
        handler.removeCallbacks(progressPoller)
        handler.removeCallbacks(alphaFallback)
        resolver?.close()
        definitionPopup?.dismiss()
        definitionPopup = null
        handler.removeCallbacks(saveTtsSettingsRunnable)
        ReaderTtsService.attach(null)
        ReaderTtsService.stop(applicationContext)
        binding.webView.destroy()
        binding.measureWebView.destroy()
        dictionaryLookup?.close()
        dictionaryLookup = null
        ttsController?.close()
        ttsController = null
        super.onDestroy()
    }

    private fun beginReadingSession(now: Long) {
        if (readingSessionStartedAt == null) {
            readingSessionStartedAt = now
            readingSessionLastInteractionAt = now
            readingSessionActiveSeconds = 0
            readingSessionStartSpine = spineIndex
            readingSessionStartPage = currentPageInChapter
        }
    }

    private fun recordReadingSession(now: Long) {
        val started = readingSessionStartedAt ?: return
        if (readingSessionLastInteractionAt > 0L) {
            val gap = ((now - readingSessionLastInteractionAt) / 1000L).coerceAtLeast(0L)
            if (gap <= SESSION_IDLE_GAP_SECONDS) readingSessionActiveSeconds += gap.toInt()
        }
        val seconds = readingSessionActiveSeconds
        if (seconds >= 10 && bookId >= 0L) {
            val session = com.epubreader.app.data.ReadingSessionEntity(
                bookId = bookId, startedAt = started, endedAt = now, activeSeconds = seconds,
                chaptersAdvanced = kotlin.math.abs(spineIndex - readingSessionStartSpine),
                pagesAdvanced = kotlin.math.abs(currentPageInChapter - readingSessionStartPage),
            )
            lifecycleScope.launch(Dispatchers.IO) { db.readingSessionDao().insert(session) }
        }
        readingSessionStartedAt = null
        readingSessionLastInteractionAt = 0L
        readingSessionActiveSeconds = 0
    }

    companion object {
        private const val SESSION_IDLE_GAP_SECONDS = 300L
        const val EXTRA_BOOK_ID = "book_id"
        private const val TTS_SETTINGS_SAVE_DELAY_MS = 800L

        /** Patch 19 (Addition #1): slide duration for the page-turn snapshot.
         *  Longer than the old 220ms crossfade so the slide reads as a page turn
         *  instead of a flicker. Tune this one number to speed up/slow down the
         *  animation app-wide. */
        const val PAGE_TURN_DURATION_MS = 340L

        // Highlight colors — stored as Int ARGB in the DB, rendered as rgba() in CSS.
        // The color Int is the full-opacity color; the CSS uses 40% opacity for a
        // subtle highlight that doesn't obscure the text underneath.
        const val HIGHLIGHT_YELLOW = 0xFFFFEB3B.toInt()
        const val HIGHLIGHT_GREEN = 0xFF66BB6A.toInt()
        const val HIGHLIGHT_BLUE = 0xFF42A5F5.toInt()
        const val HIGHLIGHT_PURPLE = 0xFFAB47BC.toInt()

        fun highlightCssColor(color: Int): String {
            val r = android.graphics.Color.red(color)
            val g = android.graphics.Color.green(color)
            val b = android.graphics.Color.blue(color)
            return "rgba($r,$g,$b,0.4)"
        }
    }

    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        currentSelectionActionMode = mode
        definitionPopup?.dismiss()
        // LivreWebView suppresses the native floating menu through the ActionMode
        // callback lifecycle, without calling ActionMode.hide()/finish() or mutating
        // the live menu from asynchronous touch callbacks. This keeps Chromium's
        // selection handles and lifecycle intact.
        binding.webView.postDelayed({ showReaderSelectionToolbar() }, 50L)
    }

    override fun onActionModeFinished(mode: ActionMode) {
        super.onActionModeFinished(mode)
        if (currentSelectionActionMode === mode) currentSelectionActionMode = null
        selectionToolbarPopup?.dismiss()
        selectionToolbarPopup = null
        currentReaderSelection = null
    }

    private fun showReaderSelectionToolbar() {
        captureCurrentSelection { selection ->
            if (selection == null || selection.text.isBlank()) return@captureCurrentSelection
            currentReaderSelection = selection
            val content = LayoutInflater.from(this).inflate(R.layout.reader_selection_toolbar, null, false)
            val copy = content.findViewById<TextView>(R.id.selection_toolbar_copy)
            val define = content.findViewById<TextView>(R.id.selection_toolbar_define)
            val highlight = content.findViewById<TextView>(R.id.selection_toolbar_highlight)
            val more = content.findViewById<ImageButton>(R.id.selection_toolbar_more)

            copy.setOnClickListener { copySelectedText() }
            define.setOnClickListener {
                val selected = currentReaderSelection ?: return@setOnClickListener
                dismissReaderSelectionToolbar(true)
                showDefinition(selected)
            }
            highlight.setOnClickListener {
                val selected = currentReaderSelection ?: return@setOnClickListener
                dismissReaderSelectionToolbar(true)
                showHighlightColorPicker(selected)
            }
            more.setOnClickListener { showSelectionMoreMenu(more, currentReaderSelection) }

            val popup = PopupWindow(
                content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true,
            ).apply {
                isOutsideTouchable = false
                isFocusable = false
                elevation = resources.getDimension(R.dimen.app_definition_card_elevation)
                setBackgroundDrawable(androidx.core.content.ContextCompat.getDrawable(this@ReaderActivity, R.drawable.reader_selection_toolbar_bg))
            }

            selectionToolbarPopup?.dismiss()
            selectionToolbarPopup = popup
            installSelectionToolbarDrag(content, popup)
            popup.showAtLocation(binding.root, Gravity.TOP or Gravity.START, toolbarX(selection), toolbarY(selection))
            positionSelectionToolbar(popup, selection)
        }
    }

    /**
     * Lets the entire custom toolbar act as a draggable surface. A tap on an
     * action view still performs that action; once the touch moves beyond the
     * normal touch slop, it becomes a toolbar drag and the action is suppressed.
     * Movement is free in both directions and clamped to the app's existing
     * popup edge margin.
     */
    private fun installSelectionToolbarDrag(content: View, popup: PopupWindow) {
        val touchSlop = ViewConfiguration.get(content.context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var lastRawX = 0f
        var lastRawY = 0f
        var dragging = false
        var moved = false
        var popupX = 0
        var popupY = 0
        var positionInitialized = false

        fun refreshPopupPosition() {
            val popupWidth = popup.contentView.measuredWidth
            val popupHeight = popup.contentView.measuredHeight
            val margin = resources.getDimensionPixelSize(R.dimen.app_screen_edge_h)
            val rootWidth = binding.root.width
            val rootHeight = binding.root.height
            val maxX = (rootWidth - popupWidth - margin).coerceAtLeast(margin)
            val maxY = (rootHeight - popupHeight - margin).coerceAtLeast(margin)
            popupX = popupX.coerceIn(margin, maxX)
            popupY = popupY.coerceIn(margin, maxY)
            popup.update(popupX, popupY, -1, -1)
        }

        fun initializePositionIfNeeded() {
            if (positionInitialized) return
            val selection = currentReaderSelection ?: return
            popupX = toolbarX(selection)
            popupY = toolbarY(selection)
            positionInitialized = true
        }

        val touchListener = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (selectionToolbarPopup !== popup) {
                        false
                    } else {
                        initializePositionIfNeeded()
                        downRawX = event.rawX
                        downRawY = event.rawY
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        dragging = true
                        moved = false
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) {
                        false
                    } else {
                        val deltaX = event.rawX - lastRawX
                        val deltaY = event.rawY - lastRawY
                        if (!moved &&
                            (kotlin.math.abs(event.rawX - downRawX) > touchSlop ||
                                kotlin.math.abs(event.rawY - downRawY) > touchSlop)
                        ) {
                            moved = true
                        }
                        if (moved) {
                            popupX += deltaX.roundToInt()
                            popupY += deltaY.roundToInt()
                            refreshPopupPosition()
                        }
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        false
                    } else {
                        val wasMoved = moved
                        dragging = false
                        moved = false
                        view.parent?.requestDisallowInterceptTouchEvent(false)

                        // A tap on an action view is still a normal action click.
                        // A drag on that same view moves the toolbar instead and
                        // must not trigger the action.
                        if (!wasMoved && view !== content && view.isClickable) {
                            view.performClick()
                        }
                        true
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    moved = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }

                else -> dragging
            }
        }

        // The root handles its own padding/background. The inner container
        // handles the spaces between actions. Action views themselves retain
        // their existing click listeners and therefore remain tappable.
        content.setOnTouchListener(touchListener)
        if (content is ViewGroup && content.childCount > 0) {
            content.getChildAt(0).setOnTouchListener(touchListener)
        }
    }

    private fun positionSelectionToolbar(popup: PopupWindow, selection: ReaderSelectionLocator) {
        // Positioning is recalculated immediately after layout so measured width
        // is available. All later movement uses the same root-relative coordinate
        // system and is clamped to the existing app popup edge margin.
        binding.root.post {
            if (selectionToolbarPopup !== popup) return@post
            val x = toolbarX(selection)
            val y = toolbarY(selection)
            popup.update(x, y, -1, -1)
        }
    }

    private fun toolbarX(selection: ReaderSelectionLocator): Int {
        val rootLocation = IntArray(2)
        val webViewLocation = IntArray(2)
        binding.root.getLocationOnScreen(rootLocation)
        binding.webView.getLocationOnScreen(webViewLocation)
        val scale = binding.webView.scale
        val center = ((selection.rectLeft + selection.rectRight) / 2f) * scale
        val widthEstimate = resources.getDimensionPixelSize(R.dimen.app_selection_toolbar_estimated_width)
        val margin = resources.getDimensionPixelSize(R.dimen.app_screen_edge_h)
        return (webViewLocation[0] + center - widthEstimate / 2f - rootLocation[0]).roundToInt()
            .coerceAtLeast(margin)
    }

    private fun toolbarY(selection: ReaderSelectionLocator): Int {
        val rootLocation = IntArray(2)
        val webViewLocation = IntArray(2)
        binding.root.getLocationOnScreen(rootLocation)
        binding.webView.getLocationOnScreen(webViewLocation)
        val scale = binding.webView.scale
        val top = webViewLocation[1] + selection.rectTop * scale
        val toolbarHeight = resources.getDimensionPixelSize(R.dimen.app_selection_toolbar_height)
        val margin = resources.getDimensionPixelSize(R.dimen.app_screen_edge_h)
        return (top - toolbarHeight - margin - rootLocation[1]).roundToInt().coerceAtLeast(margin)
    }

    private fun dismissReaderSelectionToolbar(finishActionMode: Boolean) {
        selectionToolbarPopup?.dismiss()
        selectionToolbarPopup = null
        currentReaderSelection = null
        if (finishActionMode) {
            currentSelectionActionMode?.finish()
            currentSelectionActionMode = null
        }
    }

    private fun copySelectedText() {
        val text = currentReaderSelection?.text?.trim().orEmpty()
        if (text.isBlank()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.selection_copy), text))
        dismissReaderSelectionToolbar(true)
    }

    private fun processSelectedText(action: String) {
        val text = currentReaderSelection?.text?.trim().orEmpty()
        if (text.isBlank()) return
        val intent = Intent(action).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        if (intent.resolveActivity(packageManager) != null) startActivity(Intent.createChooser(intent, getString(R.string.selection_translate)))
    }

    private fun webSearchSelectedText() {
        val text = currentReaderSelection?.text?.trim().orEmpty()
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra("query", text) }
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
        dismissReaderSelectionToolbar(true)
    }

    private fun showSelectionMoreMenu(anchor: View, selection: ReaderSelectionLocator?) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(getString(R.string.selection_web_search)).setOnMenuItemClickListener {
            webSearchSelectedText()
            true
        }
        popup.menu.add(getString(R.string.selection_translate)).setOnMenuItemClickListener {
            processSelectedText(Intent.ACTION_PROCESS_TEXT)
            true
        }
        popup.menu.add(getString(R.string.selection_share)).setOnMenuItemClickListener {
            val text = selection?.text?.trim().orEmpty()
            if (text.isNotBlank()) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, getString(R.string.selection_share)))
            }
            dismissReaderSelectionToolbar(true)
            true
        }
        popup.menu.add(getString(R.string.selection_select_all)).setOnMenuItemClickListener {
            binding.webView.evaluateJavascript("if(window.getSelection){var s=window.getSelection();s.selectAllChildren(document.body);}", null)
            popup.dismiss()
            true
        }
        popup.menu.add(getString(R.string.add_bookmark)).setOnMenuItemClickListener {
            val selected = selection ?: currentReaderSelection
            popup.dismiss()
            dismissReaderSelectionToolbar(true)
            if (selected != null) addBookmarkFromSelection(selected)
            true
        }
        popup.show()
    }

    /** Dismisses the active text-selection ActionMode (the floating toolbar with
     *  Copy / Define / Highlight / …) and clears the WebView selection ranges.
     *  Called before any navigation away from the reader page (TOC / Bookmarks /
     *  Highlights / Search / Settings / TTS overlay / chapter change) and on a
     *  fresh tap on the page, so the selection toolbar never lingers. */
    private fun clearReaderSelection() {
        dismissReaderSelectionToolbar(false)
        currentSelectionActionMode?.finish()
        currentSelectionActionMode = null
        binding.webView.evaluateJavascript(
            "if(window.getSelection){try{window.getSelection().removeAllRanges();}catch(e){}}",
            null,
        )
    }

    /** Suppresses page-turn / chrome-toggle for a short window after a tap that
     *  should not change the page (highlight tap, selection dismissal). The
     *  GestureDetector's onSingleTapConfirmed fires ~200ms after ACTION_DOWN;
     *  a 700ms window comfortably covers it. Patch v37. */
    private fun suppressReaderTap(durationMs: Long = 700L) {
        suppressReaderTapUntilMs = android.os.SystemClock.uptimeMillis() + durationMs
    }

    private fun shouldSuppressReaderTap(): Boolean =
        android.os.SystemClock.uptimeMillis() < suppressReaderTapUntilMs

    private fun showDefinition(selection: ReaderSelectionLocator?) {
        val raw = selection?.text?.trim().orEmpty()
        if (raw.isBlank()) {
            Snackbar.make(binding.root, R.string.selection_none, Snackbar.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            // Language follows the EPUB's dc:language so multi-language
            // libraries switch dictionaries automatically (falls back to
            // English when no matching dict/<lang>.db asset is bundled).
            val lang = epub?.metadata?.language
            val lookup = dictionaryLookup?.takeIf { it.matchesLanguage(lang) }
                ?: com.epubreader.app.epub.DictionaryLookup(applicationContext, lang ?: com.epubreader.app.epub.DictionaryLookup.DEFAULT_LANGUAGE)
                    .also { dictionaryLookup?.close(); dictionaryLookup = it }
            val result = lookup.lookup(raw)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                showDefinitionCard(raw, result, selection)
            }
        }
    }

    /**
     * Patch v37: contextual definition card anchored near the selection.
     *
     * Positioned above the selection when there is room, otherwise below it,
     * clamped to the screen so it never runs off either edge; falls back to a
     * bottom-anchored card when no selection rect is available. Suggestions
     * are re-lookable with a tap, and a hook hands the word off to any
     * external dictionary app via ACTION_PROCESS_TEXT.
     */
    private fun showDefinitionCard(
        raw: String,
        result: com.epubreader.app.epub.DictionaryLookup.Result,
        selection: ReaderSelectionLocator?,
    ) {
        definitionPopup?.dismiss()
        val parent = binding.root as ViewGroup
        val card = layoutInflater.inflate(R.layout.view_definition_card, parent, false)
        val wordView = card.findViewById<TextView>(R.id.dictWord)
        val noResult = card.findViewById<TextView>(R.id.dictNoResult)
        val entriesBox = card.findViewById<LinearLayout>(R.id.dictEntries)
        val suggestionsLabel = card.findViewById<TextView>(R.id.dictSuggestionsLabel)
        val suggestionsBox = card.findViewById<LinearLayout>(R.id.dictSuggestions)
        val externalButton = card.findViewById<com.google.android.material.button.MaterialButton>(R.id.dictExternalButton)

        wordView.text = raw.trim()
        val primary = themeColor(android.R.attr.textColorPrimary)
        val accent = themeColor(com.google.android.material.R.attr.colorPrimary)

        if (result.entries.isEmpty()) {
            noResult.visibility = View.VISIBLE
        } else {
            noResult.visibility = View.GONE
            result.entries.forEach { entry ->
                val line = android.text.SpannableString("${entry.partOfSpeech}  ${entry.definition}")
                line.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, entry.partOfSpeech.length, 0)
                entriesBox.addView(TextView(this).apply {
                    text = line
                    textSize = 14f
                    setTextColor(primary)
                    setPadding(0, (4 * resources.displayMetrics.density).roundToInt(), 0, 0)
                })
            }
        }

        if (result.suggestions.isNotEmpty()) {
            suggestionsLabel.visibility = View.VISIBLE
            result.suggestions.forEach { suggestion ->
                suggestionsBox.addView(TextView(this).apply {
                    text = suggestion
                    textSize = 14f
                    setTextColor(accent)
                    setPadding(0, (2 * resources.displayMetrics.density).roundToInt(), 0, 0)
                    setOnClickListener { showDefinition(ReaderSelectionLocator(suggestion, "", "", 0, "", 0)) }
                })
            }
        } else {
            suggestionsLabel.visibility = View.GONE
        }

        externalButton.visibility = View.VISIBLE
        externalButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, raw)
                putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            }
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.dictionary_lookup_external)))
            } catch (_: android.content.ActivityNotFoundException) {
                Snackbar.make(binding.root, R.string.dictionary_external_none, Snackbar.LENGTH_SHORT).show()
            }
        }

        // Persist the lookup in the offline vocabulary history (IO thread).
        if (result.entries.isNotEmpty()) {
            val bookId = bookId
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.get(applicationContext)
                val definition = result.entries.firstOrNull()?.definition
                val partOfSpeech = result.entries.firstOrNull()?.partOfSpeech
                // Prefer the dictionary's normalized match so punctuation
                // variants don't create odd history entries.
                val word = (result.matchedWord ?: raw).trim().lowercase(java.util.Locale.US)
                if (word.isBlank()) return@launch
                val existing = db.dictionaryHistoryDao().find(word)
                if (existing == null) {
                    db.dictionaryHistoryDao().insert(
                        DictionaryHistoryEntity(
                            word = word,
                            definition = definition,
                            partOfSpeech = partOfSpeech,
                            bookId = bookId,
                            lookedUpAt = System.currentTimeMillis(),
                        )
                    )
                } else {
                    // Re-lookups move the word back to the top of the list.
                    db.dictionaryHistoryDao().refresh(
                        existing.id, definition, partOfSpeech, bookId, System.currentTimeMillis(),
                    )
                }
            }
        }

        val density = resources.displayMetrics.density
        val popup = PopupWindow(card, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = resources.getDimension(R.dimen.app_definition_card_elevation)
        }
        definitionPopup = popup
        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val cardW = card.measuredWidth.coerceAtMost(resources.displayMetrics.widthPixels)
        val cardH = card.measuredHeight
        val anchorLeft: Float
        val anchorTop: Float
        val anchorBottom: Float
        if (selection != null && selection.hasRect) {
            val loc = IntArray(2)
            binding.webView.getLocationInWindow(loc)
            anchorLeft = loc[0] + selection.rectLeft * density
            anchorTop = loc[1] + selection.rectTop * density
            anchorBottom = loc[1] + selection.rectBottom * density
        } else {
            anchorLeft = 0f
            anchorTop = resources.displayMetrics.heightPixels.toFloat()
            anchorBottom = resources.displayMetrics.heightPixels.toFloat()
        }
        val margin = resources.getDimension(R.dimen.app_popup_screen_margin)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val x = (anchorLeft - cardW / 2f).roundToInt().coerceIn(margin.toInt(), (screenW - cardW - margin).toInt().coerceAtLeast(margin.toInt()))
        val y = if (anchorTop - cardH - margin >= 0) {
            // Enough room above the selection.
            (anchorTop - cardH - margin).roundToInt()
        } else if (anchorBottom + cardH + margin <= screenH) {
            // Room below.
            (anchorBottom + margin).roundToInt()
        } else {
            // Center of the screen as a last resort.
            ((screenH - cardH) / 2f).roundToInt()
        }
        popup.showAtLocation(binding.root, Gravity.NO_GRAVITY, x, y)
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    // ---------------------------------------------------------------- highlights

    /** JS interface for highlight tap callbacks from the WebView. */
    inner class HighlightBridge {
        @android.webkit.JavascriptInterface
        fun onHighlightTap(id: Long) {
            // Patch v37: suppress the page-turn / chrome-toggle that the
            // GestureDetector would otherwise fire for this tap.
            suppressReaderTap()
            runOnUiThread { showHighlightNoteSheet(id) }
        }
    }

    /** Shows a compact color picker bottom sheet for creating a highlight. */
    private fun showHighlightColorPicker(selection: ReaderSelectionLocator?) {
        if (selection == null || selection.text.isBlank()) {
            Snackbar.make(binding.root, R.string.selection_none, Snackbar.LENGTH_SHORT).show()
            return
        }
        val colors = listOf(
            HIGHLIGHT_YELLOW to R.string.highlight_color_yellow to R.drawable.highlight_color_yellow,
            HIGHLIGHT_GREEN to R.string.highlight_color_green to R.drawable.highlight_color_green,
            HIGHLIGHT_BLUE to R.string.highlight_color_blue to R.drawable.highlight_color_blue,
            HIGHLIGHT_PURPLE to R.string.highlight_color_purple to R.drawable.highlight_color_purple,
        )
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).roundToInt()
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.highlight_action)
            textSize = 16f
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).roundToInt())
        })
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        colors.forEach { (pair, drawableRes) ->
            val (colorInt, labelRes) = pair
            val btn = android.widget.ImageButton(this).apply {
                setImageResource(drawableRes)
                background = null
                val size = (48 * resources.displayMetrics.density).roundToInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins((8 * resources.displayMetrics.density).roundToInt(), 0, (8 * resources.displayMetrics.density).roundToInt(), 0)
                }
                contentDescription = getString(labelRes)
                setOnClickListener {
                    dialog.dismiss()
                    saveHighlight(selection, colorInt)
                }
            }
            colorRow.addView(btn)
        }
        root.addView(colorRow)
        dialog.setContentView(root)
        dialog.show()
    }

    /** Saves a highlight to the database and injects it into the WebView. */
    private fun saveHighlight(selection: ReaderSelectionLocator, color: Int) {
        val href = selection.spineHref
        val highlight = com.epubreader.app.data.HighlightEntity(
            bookId = bookId,
            spineHref = href,
            text = selection.text,
            color = color,
            prefix = selection.prefix,
            suffix = selection.suffix,
            startPath = selection.startPath,
            endPath = selection.endPath,
            startOffset = selection.startOffset,
            endOffset = selection.endOffset,
        )
        lifecycleScope.launch(Dispatchers.IO) {
            val id = com.epubreader.app.data.BookRepository(applicationContext).addHighlight(highlight)
            withContext(Dispatchers.Main) {
                injectHighlightIntoWebView(id, selection.text, selection.prefix, selection.suffix, color, selection.startPath, selection.endPath)
                Snackbar.make(binding.root, R.string.highlight_added, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /** Injects a single highlight into the WebView immediately after creation.
     *
     * Patch v37 anchoring strategy (most-specific first):
     *  1. Resolve the stored start element path and search for the text
     *     within that element only - this keeps a repeated phrase from
     *     matching an earlier occurrence elsewhere in the chapter.
     *  2. If the path cannot be resolved, search all chapter occurrences and
     *     score each candidate against the saved prefix and suffix context.
     */
    private fun injectHighlightIntoWebView(
        id: Long,
        text: String,
        prefix: String,
        suffix: String,
        color: Int,
        startPath: String = "",
        endPath: String = "",
    ) {
        val cssColor = highlightCssColor(color)
        val safeText = org.json.JSONObject.quote(text)
        val safePrefix = org.json.JSONObject.quote(prefix)
        val safeSuffix = org.json.JSONObject.quote(suffix)
        val safeStartPath = org.json.JSONObject.quote(startPath)
        binding.webView.evaluateJavascript(
            """(function(){
                var text=$safeText,prefix=$safePrefix,suffix=$safeSuffix,color='$cssColor',id=$id,sp=$safeStartPath;
                if(document.querySelector('mark.livre-highlight[data-highlight-id="'+id+'"]'))return true;
                function textNodes(root){var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null,false);var a=[];while(w.nextNode())a.push(w.currentNode);return a;}
                function locate(nodes,off){var acc=0;for(var i=0;i<nodes.length;i++){var len=nodes[i].textContent.length;if(off<=acc+len)return [nodes[i],off-acc];acc+=len;}return null;}
                function markNodes(sn,so,en,eo){
                    if(!sn||!en)return false;
                    function make(){var m=document.createElement('mark');m.className='livre-highlight';m.style.backgroundColor=color;m.style.borderRadius='2px';m.dataset.highlightId=id;m.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();LivreHighlight.onHighlightTap(id);});return m;}
                    try{var r=document.createRange();r.setStart(sn,so);r.setEnd(en,eo);r.surroundContents(make());return true;}catch(e){}
                    try{var r2=document.createRange();r2.setStart(sn,so);r2.setEnd(en,eo);var m2=make();m2.appendChild(r2.extractContents());r2.insertNode(m2);return true;}catch(e2){}
                    return false;
                }
                function resolveEl(p){
                    if(!p)return null;
                    var parts=p.split('/');var node=document.body;var started=false;
                    for(var i=0;i<parts.length;i++){
                        var seg=parts[i].split(':');var tag=seg[0];var idx=parseInt(seg[1]||'0',10);
                        if(tag==='body'){started=true;continue;}
                        if(!started)continue;
                        var kids=node.children,seen=0,found=null;
                        for(var j=0;j<kids.length;j++){if(kids[j].tagName.toLowerCase()===tag){if(seen===idx){found=kids[j];break;}seen++;}}
                        if(!found)return null;node=found;
                    }
                    return node;
                }
                function contextScore(all,pos){
                    var score=0;
                    if(prefix){
                        var before=all.slice(Math.max(0,pos-prefix.length),pos);
                        var common=0;
                        while(common<before.length&&common<prefix.length&&before.charAt(before.length-1-common)===prefix.charAt(prefix.length-1-common))common++;
                        score+=common*2;
                        if(before===prefix)score+=10000;
                    }
                    if(suffix){
                        var after=all.slice(pos+text.length,pos+text.length+suffix.length);
                        var commonAfter=0;
                        while(commonAfter<after.length&&commonAfter<suffix.length&&after.charAt(commonAfter)===suffix.charAt(commonAfter))commonAfter++;
                        score+=commonAfter*2;
                        if(after===suffix)score+=10000;
                    }
                    return score;
                }
                function bestOccurrence(all,needle){
                    if(!needle)return -1;
                    var best=-1,bestScore=-1;
                    var from=0,pos;
                    while((pos=all.indexOf(needle,from))>=0){
                        var score=contextScore(all,pos);
                        if(score>bestScore){bestScore=score;best=pos;}
                        from=pos+Math.max(1,needle.length);
                    }
                    return best;
                }
                var el=resolveEl(sp);
                if(el){
                    var nodes=textNodes(el);var all='';nodes.forEach(function(n){all+=n.textContent;});
                    var pos=bestOccurrence(all,text);
                    if(pos>=0){var a=locate(nodes,pos);var b=locate(nodes,pos+text.length);
                        if(a&&b&&markNodes(a[0],a[1],b[0],b[1]))return true;}
                }
                var dnodes=textNodes(document.body);var dall='';dnodes.forEach(function(n){dall+=n.textContent;});
                var tp=bestOccurrence(dall,text);
                if(tp<0)return false;
                var ep=tp+text.length;
                var a2=locate(dnodes,tp);var b2=locate(dnodes,ep);
                return !!(a2&&b2&&markNodes(a2[0],a2[1],b2[0],b2[1]));
            })();""",
            null,
        )
    }

    /**
     * Navigates from the Highlights tab to the exact rendered page containing
     * the selected highlight. A highlight is not an EPUB URL/fragment, so using
     * navigateToUrl() here can reload the same chapter and restore the current
     * page while still adding a history entry.
     */
    private fun goToHighlight(highlight: com.epubreader.app.data.HighlightEntity) {
        clearReaderSelection()
        val book = epub ?: return
        val targetIndex = book.spine.indexOfFirst { it.href == highlight.spineHref }
        if (targetIndex < 0) return

        val current = captureReaderLocation()
        val sameChapter = targetIndex == spineIndex
        if (!sameChapter) {
            // Resolve the visible WebView page before changing chapters. The
            // destination is asynchronous, but the history entry must represent
            // the exact location the user is leaving.
            pendingHighlightHistoryLocation = null
            pendingHighlightId = highlight.id
            pendingFragment = null
            pendingTargetPageInChapter = null
            restoreRatio = null
            binding.webView.evaluateJavascript(
                "(function(){if(!window.Caesura) return '';return window.Caesura.currentPage()+','+window.Caesura.ratio();})();"
            ) { result ->
                val values = result?.trim()?.removeSurrounding("\"")?.split(',')
                val actualPage = values?.getOrNull(0)?.toIntOrNull()
                val actualRatio = values?.getOrNull(1)?.toFloatOrNull()
                if (!restoringHistoryLocation && current != null) {
                    pushHistory(if (actualPage != null && actualPage >= 0) {
                        current.copy(pageInChapter = actualPage, ratio = actualRatio ?: current.ratio)
                    } else current)
                }
                pendingHistoryCursorAfterRestore = true
                loadChapter(targetIndex)
            }
            return
        }

        // Resolve the target page without changing the WebView first. This makes
        // same-chapter history deterministic: the page being left is recorded
        // before gotoPage() changes the rendered page.
        binding.webView.evaluateJavascript(
            "if(window.Caesura){window.Caesura.currentPage() + '|' + window.Caesura.highlightPageById(${highlight.id});}"
        ) { result ->
            val values = result?.trim()?.removeSurrounding("\"")?.split('|')
            val currentPage = values?.getOrNull(0)?.toIntOrNull()
            val targetPage = values?.getOrNull(1)?.toIntOrNull()

            if (targetPage != null && targetPage >= 0 && currentPage != null &&
                targetPage != currentPage && !restoringHistoryLocation && current != null
            ) {
                pushHistory(current.copy(pageInChapter = currentPage))
            }

            if (targetPage != null && targetPage >= 0) {
                pendingHistoryCursorAfterRestore = true
                binding.webView.evaluateJavascript(
                    "if(window.Caesura){window.Caesura.gotoHighlightById(${highlight.id});}"
                ) {
                    binding.webView.evaluateJavascript(
                        "if(window.Caesura){window.Caesura.currentPage()+','+window.Caesura.ratio();}"
                    ) { targetLocationResult ->
                        val targetValues = targetLocationResult?.trim()?.removeSurrounding("\"")?.split(',')
                        val targetActualPage = targetValues?.getOrNull(0)?.toIntOrNull()
                        val targetActualRatio = targetValues?.getOrNull(1)?.toFloatOrNull()
                        if (pendingHistoryCursorAfterRestore && targetActualPage != null) {
                            historyCursorLocation = ReaderLocation(spineIndex, targetActualPage, targetActualRatio ?: 0f)
                            pendingHistoryCursorAfterRestore = false
                        }
                        handler.postDelayed({ pollProgress() }, 80L)
                    }
                }
            }
        }
    }

    /** Loads all highlights for the current chapter and injects them into the WebView. */
    private fun injectHighlightsForChapter() {
        val book = epub ?: return
        val href = book.spine.getOrNull(spineIndex)?.href ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val highlights = com.epubreader.app.data.BookRepository(applicationContext)
                .getHighlightsForChapter(bookId, href)
            if (highlights.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                highlights.forEach { h ->
                    injectHighlightIntoWebView(h.id, h.text, h.prefix, h.suffix, h.color, h.startPath, h.endPath)
                }
                val targetId = pendingHighlightId
                if (targetId != null && highlights.any { it.id == targetId }) {
                    pendingHighlightId = null
                    pendingHighlightHistoryLocation = null
                    handler.postDelayed({
                        binding.webView.evaluateJavascript(
                            "if(window.Caesura){window.Caesura.gotoHighlightById($targetId);}",
                        ) {
                            // Cross-chapter highlight navigation resolves its
                            // exact rendered page only after the highlights have
                            // been injected. Keep that page as the history cursor
                            // so Back/Forward preserve the explicit destination.
                            binding.webView.evaluateJavascript(
                                "if(window.Caesura){window.Caesura.currentPage()+','+window.Caesura.ratio();}"
                            ) { result ->
                                val values = result?.trim()?.removeSurrounding("\"")?.split(',')
                                val page = values?.getOrNull(0)?.toIntOrNull()
                                val ratio = values?.getOrNull(1)?.toFloatOrNull()
                                if (page != null) {
                                    historyCursorLocation = ReaderLocation(spineIndex, page, ratio ?: 0f)
                                }
                                handler.postDelayed({ pollProgress() }, 80L)
                            }
                        }
                    }, 120L)
                }
            }
        }
    }

    /** Shows a bottom sheet for viewing a highlight and adding/editing a note. */
    private fun showHighlightNoteSheet(highlightId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val repo = com.epubreader.app.data.BookRepository(applicationContext)
            // Find the highlight by loading all highlights for this book and finding by id.
            val href = epub?.spine?.getOrNull(spineIndex)?.href ?: return@launch
            val highlights = repo.getHighlightsForChapter(bookId, href)
            val highlight = highlights.find { it.id == highlightId } ?: return@launch
            withContext(Dispatchers.Main) {
                val dialog = BottomSheetDialog(this@ReaderActivity)
                val root = LinearLayout(this@ReaderActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (16 * resources.displayMetrics.density).roundToInt()
                    setPadding(pad, pad, pad, pad)
                }
                // Highlighted text
                root.addView(TextView(this@ReaderActivity).apply {
                    text = highlight.text
                    textSize = 15f
                    setTextColor(themeColor(android.R.attr.textColorPrimary))
                    setPadding(0, 0, 0, (12 * resources.displayMetrics.density).roundToInt())
                })
                // Existing note (if any)
                if (!highlight.note.isNullOrBlank()) {
                    root.addView(TextView(this@ReaderActivity).apply {
                        text = highlight.note
                        textSize = 14f
                        setTextColor(themeColor(android.R.attr.textColorSecondary))
                        setPadding(0, 0, 0, (12 * resources.displayMetrics.density).roundToInt())
                    })
                }
                // Note input
                val input = android.widget.EditText(this@ReaderActivity).apply {
                    hint = getString(R.string.highlight_note_hint)
                    setText(highlight.note ?: "")
                    setSingleLine(false)
                    minLines = 2
                    maxLines = 4
                }
                root.addView(input)
                // Button row
                val btnRow = LinearLayout(this@ReaderActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END
                    setPadding(0, (12 * resources.displayMetrics.density).roundToInt(), 0, 0)
                }
                btnRow.addView(com.google.android.material.button.MaterialButton(this@ReaderActivity).apply {
                    text = getString(R.string.delete)
                    setOnClickListener {
                        dialog.dismiss()
                        lifecycleScope.launch(Dispatchers.IO) {
                            repo.deleteHighlight(highlight)
                            withContext(Dispatchers.Main) {
                                // Remove the highlight from the WebView.
                                binding.webView.evaluateJavascript(
                                    """(function(){var m=document.querySelector('mark.livre-highlight[data-highlight-id="$highlightId"]');if(m){var p=m.parentNode;while(m.firstChild)p.insertBefore(m.firstChild,m);p.removeChild(m);}})();""",
                                    null,
                                )
                                Snackbar
                                    .make(binding.root, R.string.highlight_deleted, Snackbar.LENGTH_LONG)
                                    .setAction(R.string.undo) {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            repo.addHighlight(highlight)
                                            withContext(Dispatchers.Main) {
                                                val currentHref = epub?.spine?.getOrNull(spineIndex)?.href
                                                if (currentHref == highlight.spineHref) {
                                                    injectHighlightIntoWebView(highlight.id, highlight.text, highlight.prefix, highlight.suffix, highlight.color, highlight.startPath, highlight.endPath)
                                                }
                                            }
                                        }
                                    }
                                    .show()
                            }
                        }
                    }
                })
                btnRow.addView(com.google.android.material.button.MaterialButton(this@ReaderActivity).apply {
                    text = getString(R.string.ok)
                    // Patch v37: add spacing between Delete and OK so they're
                    // not cramped together.
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginStart = resources.getDimensionPixelSize(R.dimen.app_section_spacing)
                    }
                    setOnClickListener {
                        val note = input.text.toString().trim().ifEmpty { null }
                        dialog.dismiss()
                        lifecycleScope.launch(Dispatchers.IO) {
                            repo.updateHighlightNote(highlightId, note)
                        }
                        Snackbar.make(binding.root, R.string.highlight_note_saved, Snackbar.LENGTH_SHORT).show()
                    }
                })
                root.addView(btnRow)
                dialog.setContentView(root)
                dialog.show()
            }
        }
    }
}
