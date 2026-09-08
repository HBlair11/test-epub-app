package com.epubreader.app

import com.epubreader.app.util.SystemBarController

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
import com.epubreader.app.data.PrefsManager
import com.epubreader.app.databinding.ActivityReaderBinding
import com.epubreader.app.epub.EpubBook
import com.epubreader.app.epub.EpubParser
import com.epubreader.app.epub.EpubResourceResolver
import com.epubreader.app.epub.EpubSearchEngine
import com.epubreader.app.epub.ReaderPageMapping
import com.epubreader.app.ui.BookmarkAdapter
import com.epubreader.app.ui.ReaderSettingsActivity
import com.epubreader.app.ui.ReaderTheme
import com.epubreader.app.ui.SearchResultAdapter
import com.epubreader.app.ui.TocAdapter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var prefs: PrefsManager

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
            db.bookDao().setCurrentlyReading(bookId)
        }

        setupWebView()
        setupMeasureWebView()
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
                        }
                    }, 140L)

                    handler.postDelayed(alphaFallback, 1500L)
                }
            }
        }
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.setOnLongClickListener { true }
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

    private fun setupChrome() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnToc.setOnClickListener { showTocBookmarks() }
        binding.btnBookmarks.setOnClickListener { showTocBookmarks(selectBookmarks = true) }
        binding.btnSearch.setOnClickListener { showSearchOverlay() }
        binding.btnSettings.setOnClickListener { showSettings() }
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
        binding.readerHistory.visibility = if (hasBack || hasForward) View.VISIBLE else View.GONE
        binding.readerHistoryBack.visibility = if (hasBack) View.VISIBLE else View.INVISIBLE
        binding.readerHistoryForward.visibility = if (hasForward) View.VISIBLE else View.INVISIBLE
        if (hasBack) binding.readerHistoryBack.text = getString(R.string.reader_history_back, historyPageLabel(backHistory.last()))
        if (hasForward) binding.readerHistoryForward.text = getString(R.string.reader_history_forward, historyPageLabel(forwardHistory.last()))
        binding.bottomBar.post { positionHistoryOverlay() }
    }

    /** Keep the transparent history row immediately above the opaque chrome bar.
     *  Because it is a sibling overlay, the EPUB page remains visible behind it. */
    private fun positionHistoryOverlay() {
        if (binding.readerHistory.visibility != View.VISIBLE || binding.bottomBar.visibility != View.VISIBLE) return
        binding.readerHistory.translationY = -(binding.bottomBar.height + 4).toFloat()
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
        binding.bottomBar.post { positionHistoryOverlay() }
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
            val path = e.href.substringBefore('#').trimStart('/')
            if (path.isBlank()) continue
            val idx = book.spine.indexOfFirst { it.href == path }
            if (idx >= 0 && !bySpine.containsKey(idx)) bySpine[idx] = e.label
        }
        tocSectionMap = bySpine
        tocSections = bySpine.toList().sortedBy { it.first }
    }

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
body * { background-color: transparent !important; }
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
            val prefix = ReaderPageMapping.prefixSums(counts)
            val inSpine = counts.getOrNull(spineIndex)?.coerceAtLeast(1) ?: 1
            val absolute = (prefix.getOrNull(spineIndex) ?: 0) + currentScrollRatio * inSpine
            if (total <= 0) 0f else (absolute / total.toFloat()).coerceIn(0f, 1f)
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
        val currentBookPage = (currentAbsoluteBookPage() + 1).coerceIn(1, total.coerceAtLeast(1))
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

        binding.overlayTabGroup.check(if (selectBookmarks) binding.btnTabBookmarks.id else binding.btnTabContents.id)
        applyOverlayTab(if (selectBookmarks) binding.btnTabBookmarks.id else binding.btnTabContents.id)

        chromeVisible = false
        binding.topBar.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.tvPageIndicator.visibility = View.GONE
        binding.tocBookmarkOverlay.visibility = View.VISIBLE
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
        val isBookmarks = checkedId == binding.btnTabBookmarks.id
        bookmarksTabActive = isBookmarks
        val tocEmpty = epub?.toc.isNullOrEmpty()
        if (isBookmarks) {
            tocRv?.visibility = View.GONE
            this.tocEmpty?.visibility = View.GONE
            bookmarkRv?.visibility = View.VISIBLE
            val bmEmpty = (bookmarkAdapter?.currentList?.isEmpty() != false)
            this.bookmarkEmpty?.visibility = if (bmEmpty) View.VISIBLE else View.GONE
        } else {
            bookmarkRv?.visibility = View.GONE
            this.bookmarkEmpty?.visibility = View.GONE
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
        handler.post(progressPoller)
        updatePageIndicator()
        updateSectionPages()
        keepScreenOnController.onResume()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        keepScreenOnController.bump()
    }

    override fun onDestroy() {
        cancelMeasurement()
        handler.removeCallbacks(progressPoller)
        handler.removeCallbacks(alphaFallback)
        resolver?.close()
        binding.webView.destroy()
        binding.measureWebView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"

        /** Patch 17 (Addition #2): slide duration for the page-turn snapshot.
         *  Longer than the old 220ms crossfade so the slide reads as a page turn
         *  instead of a flicker. Tune this one number to speed up/slow down the
         *  animation app-wide. */
        const val PAGE_TURN_DURATION_MS = 340L
    }
}
