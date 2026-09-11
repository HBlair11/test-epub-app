package com.epubreader.app

import com.epubreader.app.util.SystemBarController

import android.annotation.SuppressLint
import android.content.Intent
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
                    binding.ttsStatus.text = getString(R.string.action_read_aloud)
                }
            }
        }, { sentence, start, end ->
            // Word-level range callback: highlight the spoken word in the page.
            // Delivered on a binder thread; hop to the UI thread.
            runOnUiThread { highlightSpokenWord(sentence, start, end) }
        }, { remainingMs ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.ttsStatus.text = getString(R.string.tts_sleep_remaining, (remainingMs / 60000L).toInt() + 1)
            }
        }, {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Snackbar.make(binding.root, R.string.tts_sleep_finished, Snackbar.LENGTH_SHORT).show()
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
        binding.ttsControls.visibility = View.VISIBLE
        updateTtsServiceState(ttsController?.state == ReaderTtsController.State.PLAYING)
        lifecycleScope.launch { ttsController?.speakChapter(book.file, item.href) }
        updateTtsSentencePosition()
    }

    // ------------------------------------------------------------- patch v37 tts

    /** SeekBar progress -> engine speech rate (0.5 + N * 0.05). */
    private fun ttsRateFor(progress: Int): Float = 0.5f + progress * 0.05f

    /** SeekBar progress -> engine pitch (0.5 + N * 0.05). */
    private fun ttsPitchFor(progress: Int): Float = 0.5f + progress * 0.05f

    /** Transport row state: visibility, play/pause icon, status line. */
    private fun updateTtsControlsUi(playing: Boolean) {
        binding.ttsControls.visibility =
            if (playing || ttsController?.state == ReaderTtsController.State.PAUSED) View.VISIBLE else View.GONE
        binding.btnTtsPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnTtsPlayPause.contentDescription = getString(if (playing) R.string.tts_pause else R.string.tts_play)
        binding.btnTtsRepeat.alpha =
            if (ttsController?.repeatMode == null || ttsController?.repeatMode == ReaderTtsController.RepeatMode.OFF) 0.55f else 1f
        if (playing) {
            updateTtsSentencePosition()
        } else {
            val remaining = ttsController?.sleepTimerRemainingMs() ?: -1L
            if (remaining <= 0L) binding.ttsStatus.text = getString(R.string.action_read_aloud)
        }
    }

    private fun updateTtsSentencePosition() {
        val position = ttsController?.sentencePosition() ?: return
        binding.ttsStatus.text = getString(R.string.tts_sentence_position, position.first, position.second)
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
        binding.ttsControls.visibility = View.GONE
        clearSpokenWordHighlight()
        ReaderTtsService.stop(this)
        binding.ttsStatus.text = getString(R.string.action_read_aloud)
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
                updateTtsSentencePosition()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Bottom sheet with background-playback toggle and voice picker. */
    private fun showTtsSettingsSheet() {
        val controller = ttsController ?: return
        val dialog = BottomSheetDialog(this)
        val pad = (20 * resources.displayMetrics.density).roundToInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.tts_settings)
            textSize = 16f
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).roundToInt())
        })
        val backgroundSwitch = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            text = getString(R.string.tts_background_playback)
            isChecked = prefs.ttsBackgroundPlayback
        }
        root.addView(backgroundSwitch)
        root.addView(TextView(this).apply {
            text = getString(R.string.tts_background_playback_summary)
            textSize = 12f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).roundToInt())
        })
        backgroundSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            prefs.ttsBackgroundPlayback = checked
            updateTtsServiceState()
        }
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
                controller.voiceName = values[which]
                scheduleTtsSettingsSave()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Loads per-book rate/pitch/voice from Room (falls back to app prefs). */
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
                    binding.ttsSpeed.progress =
                        (((saved.speechRate - 0.5f) / 0.05f).roundToInt()).coerceIn(0, PrefsManager.TTS_SPEED_MAX)
                    binding.ttsPitch.progress =
                        (((saved.pitch - 0.5f) / 0.05f).roundToInt()).coerceIn(0, PrefsManager.TTS_PITCH_MAX)
                } else {
                    controller.speechRate = ttsRateFor(prefs.ttsSpeedProgress)
                    controller.pitch = ttsPitchFor(prefs.ttsPitchProgress)
                    controller.voiceName = null
                    binding.ttsSpeed.progress = prefs.ttsSpeedProgress
                    binding.ttsPitch.progress = prefs.ttsPitchProgress
                }
                binding.ttsSpeedLabel.text = String.format(java.util.Locale.US, "%.2fx", controller.speechRate)
                binding.ttsPitchLabel.text = String.format(java.util.Locale.US, "%.2fx", controller.pitch)
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

    /** Bimodal reading: tints the sentence being spoken and wraps the exact
     *  word in a stronger span, auto-turning the page when the spoken word
     *  moves off-page. */
    private fun highlightSpokenWord(sentence: String, start: Int, end: Int) {
        if (isFinishing || isDestroyed) return
        val controller = ttsController ?: return
        if (controller.state != ReaderTtsController.State.PLAYING) return
        val color = getColor(R.color.tts_word_highlight)
        val wordCss = String.format(
            java.util.Locale.US, "rgba(%d,%d,%d,0.55)",
            Color.red(color), Color.green(color), Color.blue(color),
        )
        val sentenceCss = String.format(
            java.util.Locale.US, "rgba(%d,%d,%d,0.22)",
            Color.red(color), Color.green(color), Color.blue(color),
        )
        // Full sentence: onRangeStart offsets are relative to the whole
        // chunk, so the JS side must receive the complete text.
        val key = org.json.JSONObject.quote(sentence)
        binding.webView.evaluateJavascript(
            """(function(){
                var sentence=$key,start=$start,end=$end,wordColor='$wordCss',sentenceColor='$sentenceCss';
                function unwrap(cls){var p=document.querySelector(cls);if(p){var q=p.parentNode;while(p.firstChild)q.insertBefore(p.firstChild,p);q.removeChild(p);}}
                unwrap('.livre-tts-word');unwrap('.livre-tts-sentence');
                if(!sentence)return;
                var nodes=[],all='';
                function collect(){nodes=[];all='';var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);while(w.nextNode()){nodes.push(w.currentNode);all+=w.currentNode.textContent;}}
                function locate(off){var acc=0;for(var i=0;i<nodes.length;i++){var len=nodes[i].textContent.length;if(off<acc+len)return [nodes[i],off-acc];acc+=len;}return null;}
                function wrap(a,b,cls,color){try{var r=document.createRange();r.setStart(a[0],a[1]);r.setEnd(b[0],b[1]);var s=document.createElement('span');s.className=cls;s.style.backgroundColor=color;r.surroundContents(s);return s;}catch(e){try{var r2=document.createRange();r2.setStart(a[0],a[1]);r2.setEnd(b[0],b[1]);var frag=r2.extractContents();var s2=document.createElement('span');s2.className=cls;s2.style.backgroundColor=color;s2.appendChild(frag);r2.insertNode(s2);return s2;}catch(e2){return null;}}}
                collect();
                var k=sentence.length>60?sentence.substring(0,60):sentence;
                var si=all.indexOf(k);
                if(si<0)return;
                // Sentence-level tint first.
                wrap(locate(si),locate(si+sentence.length),'livre-tts-sentence',sentenceColor);
                // The DOM just changed; re-collect before wrapping the word.
                collect();
                var a=locate(si+start),b=locate(si+end);
                if(!a||!b)return;
                var span=wrap(a,b,'livre-tts-word',wordColor);
                if(span&&window.Caesura){
                    var rect=span.getBoundingClientRect();
                    if(rect.left>=window.innerWidth-10){window.Caesura.nextPage();}
                    else if(rect.right<=10){window.Caesura.prevPage();}
                }
            })();""",
            null,
        )
    }

    private fun clearSpokenWordHighlight() {
        if (isFinishing || isDestroyed) return
        binding.webView.evaluateJavascript(
            "(function(){['livre-tts-word','livre-tts-sentence'].forEach(function(c){var p=document.querySelector('.'+c);if(p){var q=p.parentNode;while(p.firstChild)q.insertBefore(p.firstChild,p);q.removeChild(p);}});})();",
            null,
        )
    }

    private fun setupChrome() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnToc.setOnClickListener { showTocBookmarks() }
        binding.btnBookmarks.setOnClickListener { showTocBookmarks(selectBookmarks = true) }
        binding.btnSearch.setOnClickListener { showSearchOverlay() }
        binding.btnSettings.setOnClickListener { showSettings() }
        binding.btnReadAloud.setOnClickListener {
            if (ttsController?.state == ReaderTtsController.State.PLAYING || ttsController?.state == ReaderTtsController.State.PAUSED) {
                ttsController?.togglePauseResume()
            } else {
                startTtsForCurrentChapter()
            }
        }
        binding.btnTtsPlayPause.setOnClickListener { ttsController?.togglePauseResume() }
        binding.btnTtsStop.setOnClickListener { stopTtsCompletely() }
        binding.btnTtsSkipBack.setOnClickListener {
            ttsController?.skipSentence(forward = false)
            updateTtsSentencePosition()
        }
        binding.btnTtsSkipForward.setOnClickListener {
            ttsController?.skipSentence(forward = true)
            updateTtsSentencePosition()
        }
        binding.btnTtsRepeat.setOnClickListener {
            val controller = ttsController ?: return@setOnClickListener
            controller.repeatMode = when (controller.repeatMode) {
                ReaderTtsController.RepeatMode.OFF -> ReaderTtsController.RepeatMode.SENTENCE
                ReaderTtsController.RepeatMode.SENTENCE -> ReaderTtsController.RepeatMode.WORD
                ReaderTtsController.RepeatMode.WORD -> ReaderTtsController.RepeatMode.OFF
            }
            binding.btnTtsRepeat.alpha = if (controller.repeatMode == ReaderTtsController.RepeatMode.OFF) 0.55f else 1f
            val message = when (controller.repeatMode) {
                ReaderTtsController.RepeatMode.OFF -> R.string.tts_repeat_off
                ReaderTtsController.RepeatMode.SENTENCE -> R.string.tts_repeat_on
                ReaderTtsController.RepeatMode.WORD -> R.string.tts_repeat_word
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        }
        binding.btnTtsRepeat.alpha = 0.55f
        binding.btnTtsTimer.setOnClickListener { showSleepTimerMenu() }
        binding.btnTtsSettings.setOnClickListener { showTtsSettingsSheet() }

        // Patch v37: TTS speed + pitch sliders. Progress N maps to
        // 0.5 + N * 0.05 engine units (speed 0..50 = 0.5x..3.0x, default 8 =
        // 0.9x; pitch 0..30 = 0.5x..2.0x, default 10 = 1.0x). Actual applied
        // values may come from per-book TTS settings (see applyTtsSettings).
        val savedProgress = prefs.ttsSpeedProgress
        binding.ttsSpeed.max = PrefsManager.TTS_SPEED_MAX
        binding.ttsSpeed.progress = savedProgress
        val savedRate = ttsRateFor(savedProgress)
        ttsController?.speechRate = savedRate
        binding.ttsSpeedLabel.text = String.format(java.util.Locale.US, "%.2fx", savedRate)
        binding.ttsSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val rate = ttsRateFor(progress)
                ttsController?.speechRate = rate
                binding.ttsSpeedLabel.text = String.format(java.util.Locale.US, "%.2fx", rate)
                scheduleTtsSettingsSave()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.ttsSpeedProgress = sb?.progress ?: PrefsManager.DEFAULT_TTS_SPEED_PROGRESS
                scheduleTtsSettingsSave()
            }
        })
        val savedPitchProgress = prefs.ttsPitchProgress
        binding.ttsPitch.max = PrefsManager.TTS_PITCH_MAX
        binding.ttsPitch.progress = savedPitchProgress
        val savedPitch = ttsPitchFor(savedPitchProgress)
        ttsController?.pitch = savedPitch
        binding.ttsPitchLabel.text = String.format(java.util.Locale.US, "%.2fx", savedPitch)
        binding.ttsPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val pitchValue = ttsPitchFor(progress)
                ttsController?.pitch = pitchValue
                binding.ttsPitchLabel.text = String.format(java.util.Locale.US, "%.2fx", pitchValue)
                scheduleTtsSettingsSave()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.ttsPitchProgress = sb?.progress ?: PrefsManager.DEFAULT_TTS_PITCH_PROGRESS
                scheduleTtsSettingsSave()
            }
        })
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
        binding.webView.setOnTouchListener { _, event -> detector.onTouchEvent(event); false }
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
        if (!restoringHistoryLocation && current != null) {
            val targetLocation = locationForAbsolutePage(resolved)
            if (targetLocation == null || !sameLocation(current, targetLocation)) {
                pushHistory(current)
            }
        }

        if (perPageSeekerActive) {
            pendingExactSeekLocation = locationForAbsolutePage(resolved)
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
        val current = captureReaderLocation()
        if (current != null) forwardHistory.addLast(current)
        restoringHistoryLocation = true
        updateHistoryUi()
        navigateToReaderLocation(target)
    }

    private fun goForwardInReaderHistory() {
        if (forwardHistory.isEmpty()) return
        val target = forwardHistory.removeLast()
        val current = captureReaderLocation()
        if (current != null) backHistory.addLast(current)
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

          function gotoElementById(id) {
            var element = document.getElementById(id);

            if (!element) {
              var named = document.getElementsByName(id);
              if (named.length) element = named[0];
            }

            if (!element) return false;

            var x = 0;
            var node = element;

            while (node) {
              x += node.offsetLeft || 0;
              node = node.offsetParent;
            }

            gotoPage(Math.floor(x / advance()), false);
            return true;
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
              gotoElementById: gotoElementById
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
        val book = epub ?: return
        val path = url.substringAfter(EpubResourceResolver.VIRTUAL_HOST).trimStart('/')
        val parts = path.split("/", limit = 2)
        if (parts.size < 2) return
        val entryPath = parts[1].substringBefore('#').substringBefore('?')
        val frag = if ('#' in parts[1]) parts[1].substringAfter('#') else null
        val idx = book.spine.indexOfFirst { it.href == entryPath }
        if (idx >= 0) {
            if (!restoringHistoryLocation) {
                captureReaderLocation()?.let { current ->
                    pushHistory(current)
                }
            }
            pendingFragment = frag
            if (idx != spineIndex) loadChapter(idx) else {
                capturePageSnapshot(forward = false); applyPendingFragmentOrRestore()
            }
        }
    }

    private fun goToSpine(index: Int) {
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
        if (frag != null) {
            val safe = frag.replace("'", "")
            binding.webView.evaluateJavascript(
                "if(window.Caesura){window.Caesura.gotoElementById('$safe');}"
            ) {
                binding.webView.alpha = 1f
                dismissPageSnapshot()
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
                    restoringHistoryLocation = false
                    updateHistoryUi()
                    handler.post { pollProgress() }
                }
            } else {
                binding.webView.alpha = 1f
                dismissPageSnapshot()
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
    private var activeOverlayTab: Int = 0

    /** Whether the Bookmarks tab is currently shown in the TOC overlay. The
     *  bookmark DB observer fires refreshBookmarkList asynchronously; without
     *  this guard it re-shows the "empty bookmarks" hint over the TOC tab. */
    private var bookmarksTabActive = false

    private fun showTocBookmarks(selectBookmarks: Boolean = false) {
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
                onDelete = { lifecycleScope.launch(Dispatchers.IO) { db.bookmarkDao().delete(it) } }
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
                    navigateToUrl("https://${EpubResourceResolver.VIRTUAL_HOST}/$bookId/${h.spineHref.trimStart('/')}")
                    hideOverlays()
                },
                onDelete = { h ->
                    // Patch v37: delete directly from the Highlights tab.
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.highlightDao().delete(h)
                    }
                    // Also unwrap the mark from the current page if visible.
                    binding.webView.evaluateJavascript(
                        "(function(){var m=document.querySelector('mark.livre-highlight[data-highlight-id=\"" + h.id + "\"]');if(m){var p=m.parentNode;while(m.firstChild)p.insertBefore(m.firstChild,m);p.removeChild(m);}})();",
                        null,
                    )
                    Snackbar.make(binding.root, R.string.highlight_deleted, Snackbar.LENGTH_SHORT).show()
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

        binding.overlayTabGroup.check(if (selectBookmarks) binding.btnTabBookmarks.id else binding.btnTabContents.id)
        applyOverlayTab(if (selectBookmarks) binding.btnTabBookmarks.id else binding.btnTabContents.id)

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
        if (!restoringHistoryLocation) {
            captureReaderLocation()?.let { pushHistory(it) }
        }
        pendingFragment = null
        restoreRatio = b.scrollRatio
        if (b.spineIndex != spineIndex) loadChapter(b.spineIndex) else {
            capturePageSnapshot(); applyPendingFragmentOrRestore()
        }
    }

    private fun addBookmark() {
        val ratio = currentScrollRatio
        val idx = spineIndex
        val title = sectionLabel()
        lifecycleScope.launch(Dispatchers.IO) {
            if (db.bookmarkDao().existsNear(bookId, idx, ratio)) {
                db.bookmarkDao().deleteNear(bookId, idx, ratio)
                withContext(Dispatchers.Main) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.delete),
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                binding.webView.evaluateJavascript(
                    "(function(){var s=window.getSelection?window.getSelection().toString():'';return (s||document.body.innerText||'').slice(0,80).replace(/\\\\s+/g,' ');})();"
                ) { result ->
                    val snippet = result?.trim('\"')?.replace("\\\"", "\"")?.replace("\\n", " ") ?: ""
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.bookmarkDao().insert(
                            BookmarkEntity(
                                bookId = bookId,
                                spineIndex = idx,
                                scrollRatio = ratio,
                                chapterTitle = title,
                                snippet = snippet
                            )
                        )
                        withContext(Dispatchers.Main) {
                            Snackbar.make(
                                binding.root,
                                R.string.bookmark_added,
                                Snackbar.LENGTH_SHORT
                            ).show()
                        }
                    }
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
        settingsLauncher.launch(Intent(this, ReaderSettingsActivity::class.java))
    }

    private val settingsLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                applySettingsAndReload()
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
        restoreRatio = currentScrollRatio
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
            binding.ttsControls.visibility = View.GONE
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
        private const val HIGHLIGHT_ACTION_ID = 0x4C48
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
        // Patch v37: Define and Highlight live directly in the floating
        // selection toolbar (the old auto-popup bottom sheet captured the
        // selection too early — onActionModeStarted fires when the selection
        // begins, often with only the first word — and its buttons then used
        // that stale selection). Both actions below capture the selection at
        // click time instead, which is what a standard reader does.
        definitionPopup?.dismiss()
        val menu = mode.menu
        val defineId = 0x4C59
        if (menu.findItem(defineId) == null) {
            val item = menu.add(0, defineId, 0, getString(R.string.selection_define))
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            item.setOnMenuItemClickListener {
                captureCurrentSelection { selection -> showDefinition(selection) }
                mode.finish()
                true
            }
        }
        if (menu.findItem(HIGHLIGHT_ACTION_ID) == null) {
            val item = menu.add(0, HIGHLIGHT_ACTION_ID, 1, getString(R.string.highlight_action))
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            item.setOnMenuItemClickListener {
                captureCurrentSelection { selection -> showHighlightColorPicker(selection) }
                mode.finish()
                true
            }
        }
    }

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
     *  2. Prefix-anchored search across the whole chapter text.
     *  3. Bare text search as the last resort.
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
                    function make(){var m=document.createElement('mark');m.className='livre-highlight';m.style.backgroundColor=color;m.style.borderRadius='2px';m.dataset.highlightId=id;m.addEventListener('click',function(e){e.stopPropagation();LivreHighlight.onHighlightTap(id);});return m;}
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
                var el=resolveEl(sp);
                if(el){
                    var nodes=textNodes(el);var all='';nodes.forEach(function(n){all+=n.textContent;});
                    var pos=all.indexOf(text);
                    if(pos>=0){var a=locate(nodes,pos);var b=locate(nodes,pos+text.length);
                        if(a&&b&&markNodes(a[0],a[1],b[0],b[1]))return true;}
                }
                var dnodes=textNodes(document.body);var dall='';dnodes.forEach(function(n){dall+=n.textContent;});
                var searchStart=0;
                if(prefix&&prefix.length>0){var pp=dall.indexOf(prefix,searchStart);if(pp>=0)searchStart=pp+prefix.length;}
                var tp=dall.indexOf(text,searchStart);
                if(tp<0)tp=dall.indexOf(text);
                if(tp<0)return false;
                var ep=tp+text.length;
                var a2=locate(dnodes,tp);var b2=locate(dnodes,ep);
                return !!(a2&&b2&&markNodes(a2[0],a2[1],b2[0],b2[1]));
            })();""",
            null,
        )
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
                        }
                        // Remove the highlight from the WebView
                        binding.webView.evaluateJavascript(
                            """(function(){var m=document.querySelector('mark.livre-highlight[data-highlight-id="$highlightId"]');if(m){var p=m.parentNode;while(m.firstChild)p.insertBefore(m.firstChild,m);p.removeChild(m);}})();""",
                            null,
                        )
                    }
                })
                btnRow.addView(com.google.android.material.button.MaterialButton(this@ReaderActivity).apply {
                    text = getString(R.string.ok)
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
