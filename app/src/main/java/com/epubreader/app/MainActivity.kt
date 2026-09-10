package com.epubreader.app

import com.epubreader.app.util.SystemBarController

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import android.view.Menu
import android.widget.Toast
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.data.BookEntity
import com.bumptech.glide.Glide
import com.epubreader.app.data.BookRepository
import com.epubreader.app.data.PrefsManager
import com.epubreader.app.databinding.ActivityMainBinding
import com.epubreader.app.epub.BookFileTypes
import com.epubreader.app.epub.EpubImporter
import com.epubreader.app.epub.MetadataRefreshReportStore
import com.epubreader.app.epub.RescanDecision
import com.epubreader.app.ui.BookAdapter
import com.epubreader.app.ui.BookshelfViewModel
import com.epubreader.app.ui.DisplayItem
import com.epubreader.app.ui.DrawerAdapter
import com.epubreader.app.ui.DrawerItem
import com.epubreader.app.ui.HomeBookAdapter
import com.epubreader.app.ui.HomeContent
import com.epubreader.app.ui.RowAdapter
import com.epubreader.app.ui.ShelfView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val STATE_SHELF = "state_shelf"
private const val STATE_DETAIL_NAME = "state_detail_name"

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager
    private lateinit var importer: EpubImporter
    private lateinit var drawerAdapter: DrawerAdapter
    private lateinit var drawerToggle: ActionBarDrawerToggle

    private var backPressedOnce = false
    private val backResetHandler = Handler(Looper.getMainLooper())

    // Patch 12: the folder scan runs as a tracked background job so it survives
    // in-app navigation (the user can leave the Folders screen while a scan is
    // running and it keeps going). The reference is also used to guard against
    // launching a duplicate concurrent scan when the user taps Scan Now again.
    private var scanJob: Job? = null

    private val viewModel: BookshelfViewModel by viewModels {
        BookshelfViewModel.Factory(BookRepository(applicationContext), PrefsManager(applicationContext))
    }

    private var bookAdapter: BookAdapter? = null
    private var rowAdapter: RowAdapter? = null
    private var currentBooks: List<BookEntity> = emptyList()
    private var touchHelper: ItemTouchHelper? = null
    private var scrollToTopOnNextContent = false

    // Patch 11 "Screen On" controller (see util/KeepScreenOnController.kt).
    private lateinit var keepScreenOnController: com.epubreader.app.util.KeepScreenOnController

    // ---------------------------------------------------------------- scroll restoration
    // Patch 11: the bookshelf must remember the exact scroll position of the
    // Library / Authors list / Series list / Author-detail books / Series-detail
    // books layout when the user opens a book (or book details) and then comes
    // back via the phone back button or the toolbar back arrow. The remembered
    // position is restored so the clicked title (P / Z / whatever was on screen)
    // is still visible on return.
    //
    // Only the *return-from-reader/detail* path restores scroll. Switching
    // between drawer sections (and sort / view-mode changes) still do a clean
    // top reset exactly like Patch 10 — those paths set scrollToTopOnNextContent
    // which the restore path never touches. The two behaviours are mutually
    // exclusive by design: a return from the reader does NOT set the top-reset
    // flag, so the normal adapter submit path runs and the restore is applied
    // inside that path's commit callback (after the adapter has items).
    //
    // Currently Reading is intentionally excluded: it always refreshes cleanly
    // from the top on return (matches Patch 10 behaviour for that one view).
    //
    // Per-view saved scroll anchor. We store BOTH the first-visible row (for
    // exact same-layout restoration when the list order hasn't changed) and the
    // clicked book's id + position + top offset (for re-location when the list
    // re-sorted after reading progress changed). On restore we only relocate by
    // book id if the clicked book's current adapter index differs from its saved
    // index — otherwise we restore the first-visible position + offset exactly.
    private data class ScrollAnchor(
        val firstVisiblePosition: Int,
        val firstVisibleOffset: Int,
        val clickedBookId: Long?,
        val clickedBookPosition: Int,
        val clickedBookTopOffset: Int,
    )

    private val savedScroll = mutableMapOf<String, ScrollAnchor>()
    private var pendingRestoreKey: String? = null

    // Patch 16 (Issue #3): the "Recently Added" temp screen is transient — it is
    // only reachable via the "Show" action on the scan-complete snackbar and only
    // until the user presses back. This holds the shelf the user was viewing
    // before tapping Show, so back / the toolbar back arrow returns there exactly
    // instead of dumping them on the Library. Null when not on the temp screen.
    private var viewBeforeRecentlyAdded: ShelfView? = null
    private var pendingRestoreBookId: Long? = null

    // Patch 11: When opening a book FROM Currently Reading, we want the clean
    // top-reset-on-return behaviour Patch 10 gave (that view is excluded from
    // scroll restoration). Room may not re-emit in time (or at all) on resume,
    // so this flag forces a scroll-to-top in onResume as a guaranteed fallback
    // when returning from the reader to Currently Reading specifically.
    private var pendingReadingTopReset = false

    private val homeAdapters = mutableMapOf<Int, HomeBookAdapter>()

    // A list of shelf views that should be restored on return from a sub-activity.
    // Reading is deliberately NOT in this set.
    private fun isRestoreEligible(view: ShelfView): Boolean =
        view is ShelfView.Library ||
                view is ShelfView.AuthorsList ||
                view is ShelfView.SeriesList ||
                view is ShelfView.AuthorDetail ||
                view is ShelfView.SeriesDetail

    private fun viewKey(view: ShelfView): String = when (view) {
        is ShelfView.AuthorDetail -> "author_detail:${view.name}"
        is ShelfView.SeriesDetail -> "series_detail:${view.name}"
        else -> view::class.simpleName ?: "unknown"
    }

    /** Captures the current first-visible row + its pixel offset (and, for book
     *  lists, the clicked book's id + adapter position + top offset) so the exact
     *  same layout can be restored later. If the list re-sorts after reading
     *  progress changes, the clicked title is re-located by id and aligned by
     *  its saved top offset — but only if its index actually moved; otherwise the
     *  exact first-visible position + offset is restored unchanged. */
    private fun captureScrollState(clickedBookId: Long? = null) {
        val view = viewModel.view.value ?: return
        if (!isRestoreEligible(view)) return
        val lm = binding.recycler.layoutManager as? LinearLayoutManager ?: return
        val adapter = binding.recycler.adapter ?: return
        if (adapter.itemCount == 0) return
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return
        val child = lm.findViewByPosition(pos)
        val offset = child?.top ?: 0
        // For book lists, if we know which book was tapped, also capture its own
        // adapter position + top offset so we can re-align it by id if the list
        // reorders. For row lists (authors/series) there is no tapped book.
        val clickedPos = if (clickedBookId != null && adapter is BookAdapter) {
            adapter.currentList.indexOfFirst { it.id == clickedBookId }
        } else {
            -1
        }
        val clickedOffset = if (clickedPos >= 0) {
            lm.findViewByPosition(clickedPos)?.top ?: offset
        } else {
            0
        }
        savedScroll[viewKey(view)] =
            ScrollAnchor(pos, offset, clickedBookId, clickedPos, clickedOffset)
    }

    private fun tryRestoreScroll(): Boolean {
        val view = viewModel.view.value ?: return false
        if (!isRestoreEligible(view)) return false
        // Key guard: only restore if the CURRENT view is the one we queued a
        // restore for. Without this a parent-list restore could fire while a
        // detail list (or a different author/series) is on screen, or vice
        // versa, producing a wrong jump. If it doesn't match yet, keep waiting
        // (don't clear the pending key) — a later emission for the right view
        // will consume it.
        val key = pendingRestoreKey ?: return false
        if (viewKey(view) != key) return false
        val lm = binding.recycler.layoutManager as? LinearLayoutManager ?: return false
        val adapter = binding.recycler.adapter ?: return false
        if (adapter.itemCount == 0) return false
        val anchor = savedScroll[key] ?: run { pendingRestoreKey = null; return false }
        // Restore strategy: if the clicked book is still at the SAME adapter
        // index it had when we navigated away (i.e. the list didn't reorder),
        // restore the exact first-visible position + offset — the same rows
        // that were on screen before. Only when the clicked book MOVED (sort by
        // progress / last-read changed its position) do we re-locate it by id
        // and align it to its saved top offset.
        val pos: Int
        val offset: Int
        if (adapter is BookAdapter && anchor.clickedBookId != null && anchor.clickedBookPosition >= 0) {
            val currentClickedIdx = adapter.currentList.indexOfFirst { it.id == anchor.clickedBookId }
            if (currentClickedIdx >= 0 && currentClickedIdx != anchor.clickedBookPosition) {
                // Book moved: re-locate by id, align to its saved top offset.
                pos = currentClickedIdx
                offset = anchor.clickedBookTopOffset
            } else {
                // Unchanged (or book not found): restore exact first-visible.
                pos = anchor.firstVisiblePosition.coerceIn(0, adapter.itemCount - 1)
                offset = anchor.firstVisibleOffset
            }
        } else {
            pos = anchor.firstVisiblePosition.coerceIn(0, adapter.itemCount - 1)
            offset = anchor.firstVisibleOffset
        }
        lm.scrollToPositionWithOffset(pos, offset)
        pendingRestoreKey = null
        return true
    }

    private val openTreeLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri -> uri?.let { scanFolder(it, fromRefresh = false) } }

    private val openMultiFileLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris -> importMultiple(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = PrefsManager(applicationContext)
        importer = EpubImporter(applicationContext)
        keepScreenOnController = com.epubreader.app.util.KeepScreenOnController(this, prefs)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarController.apply(this)
        setSupportActionBar(binding.toolbar)

        restoreViewFromSavedState(savedInstanceState)
        setupDrawer()
        setupRecycler()
        setupObservers()
        binding.fabScan.setOnClickListener { onFabClicked() }
        binding.homeContent.homeEmptyAction.setOnClickListener {
            openMultiFileLauncher.launch(BookFileTypes.acceptedMimeTypes)
        }
        updateFab(viewModel.view.value ?: ShelfView.Home)

        // Patch 18 (Addition #3): if launched by the system "Open with" for an
        // .epub, import it and jump straight into the reader.
        handleViewIntent(intent)

        // Hamburger button that opens/closes the drawer (renders + animates
        // itself; works reliably even though the toolbar is the action bar).
        drawerToggle =
            ActionBarDrawerToggle(
                this,
                binding.drawerRoot,
                binding.toolbar,
                R.string.nav_open,
                R.string.nav_close,
            ).also { toggle ->
                binding.drawerRoot.addDrawerListener(toggle)
                toggle.syncState()
            }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        binding.drawerRoot.isOpen -> binding.drawerRoot.close()
                        viewModel.view.value is ShelfView.RecentlyAdded -> {
                            exitRecentlyAdded()
                        }

                        viewModel.isDetailOpen() -> {
                            returnToParentList()
                        }

                        else -> {
                            if (backPressedOnce) {
                                backResetHandler.removeCallbacksAndMessages(null)
                                finish()
                                return
                            }
                            backPressedOnce = true
                            Snackbar.make(binding.root, R.string.back_again_to_exit, Snackbar.LENGTH_SHORT)
                                .addCallback(object : Snackbar.Callback() {
                                    override fun onDismissed(s: Snackbar?, event: Int) {
                                        backPressedOnce = false
                                    }
                                })
                                .show()
                        }
                    }
                }
            },
        )
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::drawerToggle.isInitialized) drawerToggle.onConfigurationChanged(newConfig)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val current = viewModel.view.value ?: ShelfView.Home
        if (current !is ShelfView.RecentlyAdded) {
            outState.putString(STATE_SHELF, current.keyForSavedState())
            when (current) {
                is ShelfView.AuthorDetail -> outState.putString(STATE_DETAIL_NAME, current.name)
                is ShelfView.SeriesDetail -> outState.putString(STATE_DETAIL_NAME, current.name)
                else -> Unit
            }
        }
    }

    private fun restoreViewFromSavedState(savedInstanceState: Bundle?) {
        val key = savedInstanceState?.getString(STATE_SHELF) ?: return
        val detailName = savedInstanceState.getString(STATE_DETAIL_NAME)
        val restored = when (key) {
            BookshelfViewModel.KEY_HOME -> ShelfView.Home
            BookshelfViewModel.KEY_READING -> ShelfView.Reading
            BookshelfViewModel.KEY_LIBRARY -> ShelfView.Library
            BookshelfViewModel.KEY_FAVORITES -> ShelfView.Favorites
            BookshelfViewModel.KEY_AUTHORS -> ShelfView.AuthorsList
            BookshelfViewModel.KEY_SERIES -> ShelfView.SeriesList
            BookshelfViewModel.KEY_FINISHED -> ShelfView.Finished
            BookshelfViewModel.KEY_TBR -> ShelfView.ToBeRead
            BookshelfViewModel.KEY_COLLECTIONS -> ShelfView.Collections
            BookshelfViewModel.KEY_FOLDERS -> ShelfView.Folders
            BookshelfViewModel.KEY_SETTINGS -> ShelfView.Settings
            "author_detail" -> detailName?.let { ShelfView.AuthorDetail(it) }
            "series_detail" -> detailName?.let { ShelfView.SeriesDetail(it) }
            else -> null
        }
        restored?.let { viewModel.setView(it) }
    }

    private fun ShelfView.keyForSavedState(): String = when (this) {
        is ShelfView.Home -> BookshelfViewModel.KEY_HOME
        is ShelfView.Reading -> BookshelfViewModel.KEY_READING
        is ShelfView.Library -> BookshelfViewModel.KEY_LIBRARY
        is ShelfView.Favorites -> BookshelfViewModel.KEY_FAVORITES
        is ShelfView.AuthorsList -> BookshelfViewModel.KEY_AUTHORS
        is ShelfView.SeriesList -> BookshelfViewModel.KEY_SERIES
        is ShelfView.Finished -> BookshelfViewModel.KEY_FINISHED
        is ShelfView.ToBeRead -> BookshelfViewModel.KEY_TBR
        is ShelfView.Collections -> BookshelfViewModel.KEY_COLLECTIONS
        is ShelfView.Folders -> BookshelfViewModel.KEY_FOLDERS
        is ShelfView.Settings -> BookshelfViewModel.KEY_SETTINGS
        is ShelfView.AuthorDetail -> "author_detail"
        is ShelfView.SeriesDetail -> "series_detail"
        else -> BookshelfViewModel.KEY_HOME
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Patch 18 (Addition #3): re-handle a VIEW intent delivered to the
        // already-running (singleTop) instance.
        handleViewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        keepScreenOnController.onResume()
        if (pendingReadingTopReset) {
            pendingReadingTopReset = false
            binding.recycler.post {
                (binding.recycler.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
                binding.recycler.scrollToPosition(0)
            }
        }
        // Returning from the reader / book-details activity lands here. If a
        // restore was queued and the list content has already re-emitted before
        // this point, try once more now (harmless if there is nothing to restore
        // or the adapter is still empty — tryRestoreScroll guards both).
        if (pendingRestoreKey != null) {
            binding.recycler.post { tryRestoreScroll() }
        }
    }

    override fun onPause() {
        super.onPause()
        keepScreenOnController.onPause()
    }

    // ---------------------------------------------------------------- drawer
    private fun toggleDrawer() {
        if (binding.drawerRoot.isOpen) binding.drawerRoot.close() else binding.drawerRoot.open()
    }

    private fun setupDrawer() {
        // Patch 16 (Addition #1): drawer widened from 3/5 (60%) to 70% of
        // screen width.
        val w = resources.displayMetrics.widthPixels
        binding.drawerPane.layoutParams =
            (binding.drawerPane.layoutParams as androidx.drawerlayout.widget.DrawerLayout.LayoutParams)
                .apply { width = (w * 70 / 100).coerceAtLeast(280) }

        drawerAdapter = DrawerAdapter { item -> selectDrawer(item) }
        binding.drawerList.layoutManager = LinearLayoutManager(this)
        binding.drawerList.adapter = drawerAdapter
        submitDrawerItems()
    }

    private fun drawerItems(): List<DrawerItem> =
        listOf(
            DrawerItem(getString(R.string.nav_home), R.drawable.ic_home, view = ShelfView.Home),
            DrawerItem(getString(R.string.nav_currently_reading), R.drawable.ic_book, view = ShelfView.Reading),
            DrawerItem(getString(R.string.nav_library), R.drawable.ic_library, view = ShelfView.Library),
            DrawerItem(getString(R.string.nav_favorites), R.drawable.ic_favorite, view = ShelfView.Favorites),
            DrawerItem(getString(R.string.nav_authors), R.drawable.ic_person, view = ShelfView.AuthorsList),
            DrawerItem(getString(R.string.nav_series), R.drawable.ic_series, view = ShelfView.SeriesList),
            DrawerItem(
                getString(R.string.nav_collections),
                R.drawable.ic_collections,
                isPlaceholder = true,
                view = ShelfView.Collections
            ),
            DrawerItem(
                label = "",
                iconRes = 0,
                isDivider = true,
            ),
            DrawerItem(getString(R.string.nav_folders), R.drawable.ic_folder, view = ShelfView.Folders),
            DrawerItem(
                getString(R.string.nav_settings),
                R.drawable.ic_settings,
                isPlaceholder = true,
                view = ShelfView.Settings
            ),
        )

    private fun submitDrawerItems() {
        drawerAdapter.submitList(drawerItems())
        drawerAdapter.setSelected(viewModel.view.value)
    }

    private fun titleFor(view: ShelfView?): String =
        when (view) {
            is ShelfView.Home -> getString(R.string.nav_home)
            is ShelfView.Reading -> getString(R.string.nav_currently_reading)
            is ShelfView.Library -> getString(R.string.nav_library)
            is ShelfView.Favorites -> getString(R.string.nav_favorites)
            is ShelfView.Finished -> getString(R.string.nav_finished)
            is ShelfView.ToBeRead -> getString(R.string.nav_tbr)
            is ShelfView.RecentlyAdded -> getString(R.string.recently_added_screen_title)
            is ShelfView.AuthorsList -> getString(R.string.nav_authors)
            is ShelfView.SeriesList -> getString(R.string.nav_series)
            is ShelfView.AuthorDetail -> view.name
            is ShelfView.SeriesDetail -> view.name
            is ShelfView.Collections -> getString(R.string.nav_collections)
            is ShelfView.Folders -> getString(R.string.nav_folders)
            is ShelfView.Settings -> getString(R.string.nav_settings)
            null -> getString(R.string.app_name)
        }

    private fun selectDrawer(item: DrawerItem) {
        binding.drawerRoot.close()
        item.view?.let {
            // Navigating to any shelf view via the drawer should land at the
            // top of that list (title-ascending by default) rather than
            // preserving the scroll position of the previous view. This is the
            // Patch 10 clean-refresh-on-nav-switch path and must NOT be affected
            // by Patch 11 scroll restoration.
            scrollToTopOnNextContent = true
            // Switching sections invalidates any pending restore — the user is
            // intentionally leaving the previous view, so we don't restore it.
            pendingRestoreKey = null
            pendingReadingTopReset = false
            viewModel.setView(it)
        }
    }

    private fun setupHomeShelves() {
        val specs = listOf(
            R.id.homeRecentlyAdded to binding.homeContent.homeRecentlyAdded,
            R.id.homeFavorites to binding.homeContent.homeFavorites,
            R.id.homeAuthorBooks1 to binding.homeContent.homeAuthorBooks1,
            R.id.homeAuthorBooks2 to binding.homeContent.homeAuthorBooks2,
            R.id.homeAuthorBooks3 to binding.homeContent.homeAuthorBooks3,
            R.id.homeSeriesBooks1 to binding.homeContent.homeSeriesBooks1,
            R.id.homeSeriesBooks2 to binding.homeContent.homeSeriesBooks2,
            R.id.homeSeriesBooks3 to binding.homeContent.homeSeriesBooks3,
        )
        specs.forEach { (key, recycler) ->
            val adapter = HomeBookAdapter(::openBook, ::showBookOptions)
            recycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            recycler.adapter = adapter
            homeAdapters[key] = adapter
        }
    }

    private fun renderHome(content: HomeContent) {
        if (viewModel.view.value !is ShelfView.Home) return

        binding.homeContent.homeEmpty.visibility = if (content.hasBooks) View.GONE else View.VISIBLE
        binding.homeContent.homeRecentlyAddedSection.visibility = if (content.recentlyAdded.isEmpty()) View.GONE else View.VISIBLE
        val hasContinueReading = content.continueReading != null
        binding.homeContent.homeContinueSection.visibility = if (hasContinueReading) View.VISIBLE else View.GONE
        binding.homeContent.homeContinueCard.visibility = if (hasContinueReading) View.VISIBLE else View.GONE

        content.continueReading?.let { book ->
            binding.homeContent.homeContinueTitle.text = book.title
            binding.homeContent.homeContinueAuthor.text = book.author.ifBlank { getString(R.string.unknown_author) }

            val seriesText = book.series
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { series ->
                    val index = book.seriesIndex
                    if (index != null) {
                        val formattedIndex = if (index % 1.0 == 0.0) index.toInt().toString() else index.toString()
                        getString(R.string.home_continue_series, "$series #$formattedIndex")
                    } else {
                        getString(R.string.home_continue_series, series)
                    }
                }
            binding.homeContent.homeContinueSeries.text = seriesText
            binding.homeContent.homeContinueSeries.visibility = if (seriesText.isNullOrBlank()) View.GONE else View.VISIBLE

            val chapterNumber = (book.spineIndex + 1).coerceAtLeast(1)
            binding.homeContent.homeContinueChapter.text = if (book.spineCount > 0) {
                getString(R.string.home_continue_chapter, chapterNumber.coerceAtMost(book.spineCount), book.spineCount)
            } else {
                getString(R.string.home_continue_chapter_unknown_total, chapterNumber)
            }
            binding.homeContent.homeContinueProgress.text = if (book.progress >= 0.995f) {
                getString(R.string.progress_completed)
            } else {
                getString(R.string.home_progress_percent, (book.progress * 100).toInt())
            }
            loadHomeCover(book, binding.homeContent.homeContinueCover)
            binding.homeContent.homeContinueButton.setOnClickListener { openBook(book) }
            binding.homeContent.homeContinueCard.setOnClickListener { openBook(book) }
        }

        submitHomeShelf(R.id.homeRecentlyAdded, content.recentlyAdded)
        binding.homeContent.homeFavoritesSection.visibility = if (content.favorites.isEmpty()) View.GONE else View.VISIBLE
        submitHomeShelf(R.id.homeFavorites, content.favorites)
        bindHomeGroups(
            listOf(
                Triple(binding.homeContent.homeAuthorGroup1, binding.homeContent.homeAuthorTitle1, binding.homeContent.homeAuthorBooks1),
                Triple(binding.homeContent.homeAuthorGroup2, binding.homeContent.homeAuthorTitle2, binding.homeContent.homeAuthorBooks2),
                Triple(binding.homeContent.homeAuthorGroup3, binding.homeContent.homeAuthorTitle3, binding.homeContent.homeAuthorBooks3),
            ),
            content.topAuthors,
        )
        binding.homeContent.homeAuthorsSection.visibility = if (content.topAuthors.isEmpty()) View.GONE else View.VISIBLE
        bindHomeGroups(
            listOf(
                Triple(binding.homeContent.homeSeriesGroup1, binding.homeContent.homeSeriesTitle1, binding.homeContent.homeSeriesBooks1),
                Triple(binding.homeContent.homeSeriesGroup2, binding.homeContent.homeSeriesTitle2, binding.homeContent.homeSeriesBooks2),
                Triple(binding.homeContent.homeSeriesGroup3, binding.homeContent.homeSeriesTitle3, binding.homeContent.homeSeriesBooks3),
            ),
            content.topSeries,
        )
        binding.homeContent.homeSeriesSection.visibility = if (content.topSeries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bindHomeGroups(
        slots: List<Triple<View, android.widget.TextView, RecyclerView>>,
        groups: List<com.epubreader.app.ui.HomeGroup>,
    ) {
        slots.forEachIndexed { index, (container, title, recycler) ->
            val group = groups.getOrNull(index)
            container.visibility = if (group == null) View.GONE else View.VISIBLE
            if (group != null) {
                title.text = getString(R.string.home_group_title, group.name, group.count)
                homeAdapters[recycler.id]?.submitList(group.books)
            }
        }
    }

    private fun submitHomeShelf(id: Int, books: List<BookEntity>) {
        homeAdapters[id]?.submitList(books)
    }

    private fun loadHomeCover(book: BookEntity, view: android.widget.ImageView) {
        if (book.coverPath != null) {
            Glide.with(view).load(File(book.coverPath)).centerCrop().placeholder(R.drawable.cover_frame).into(view)
        } else {
            Glide.with(view).clear(view)
            view.setImageResource(R.drawable.cover_frame)
        }
    }

    // ---------------------------------------------------------------- recycler
    private fun setupRecycler() {
        binding.refresh.setOnRefreshListener { rescanSelectedFolder() }
        setupHomeShelves()
    }

    private fun isBookView(view: ShelfView) =
        view !is ShelfView.Home &&
                view !is ShelfView.AuthorsList &&
                view !is ShelfView.SeriesList &&
                view !is ShelfView.Collections &&
                view !is ShelfView.Folders &&
                view !is ShelfView.Settings

    private fun isPlaceholder(view: ShelfView) = view is ShelfView.Collections

    private fun isFoldersView(view: ShelfView) = view is ShelfView.Folders

    /** Returns from an Author/Series detail (books layout) back to its parent
     *  list (Authors or Series). Patch 11: the parent list's last scroll
     *  position is restored — NOT top-reset — so the author/series row the user
     *  originally tapped is still on screen. The toolbar icon flips back to the
     *  hamburger menu (handled by applyView via isDrawerIndicatorEnabled). */
    private fun returnToParentList() {
        val parent = when (viewModel.view.value) {
            is ShelfView.AuthorDetail -> ShelfView.AuthorsList
            is ShelfView.SeriesDetail -> ShelfView.SeriesList
            else -> return
        }
        pendingRestoreKey = viewKey(parent)
        viewModel.clearDetail()
    }

    // Patch 16 (Issue #3): leave the transient "Recently Added" temp screen and
    // return to whatever shelf the user was viewing before they tapped Show on
    // the scan-complete snackbar. Falls back to Library if state was lost (e.g.
    // process death). Crucially this does NOT go through setView()-persistence
    // for the RecentlyAdded view itself (see ViewModel.setView) and restores the
    // real underlying shelf, so lastView stays correct for the next launch.
    private fun exitRecentlyAdded() {
        val target = viewBeforeRecentlyAdded ?: ShelfView.Library
        viewBeforeRecentlyAdded = null
        // Returning to the previous shelf should land at the top of that list
        // (title-ascending), matching the clean-refresh-on-nav-switch contract
        // used by selectDrawer().
        scrollToTopOnNextContent = true
        viewModel.setView(target)
    }

    private fun applyView(view: ShelfView) {
        // Up affordance: top-level views show the hamburger (opens drawer);
        // detail views (author/series) and the Patch 16 "Recently Added" temp
        // screen show a back arrow that returns to the previous view — it must
        // NOT open the drawer. (Bug: previously the toggle intercepted the
        // back-arrow click and opened the drawer instead.)
        val isDetail = view is ShelfView.AuthorDetail || view is ShelfView.SeriesDetail
        val isRecentlyAdded = view is ShelfView.RecentlyAdded
        drawerToggle.isDrawerIndicatorEnabled = !isDetail && !isRecentlyAdded
        if (isDetail || isRecentlyAdded) {
            // Patch 12: when the drawer indicator is disabled, ActionBarDrawerToggle
            // draws NO icon on its own — so the back arrow was invisible on
            // author/series detail. Explicitly set the up-indicator drawable so the
            // back arrow renders and is tappable.
            drawerToggle.setHomeAsUpIndicator(R.drawable.ic_arrow_back)
            drawerToggle.setToolbarNavigationClickListener {
                if (isRecentlyAdded) exitRecentlyAdded() else returnToParentList()
            }
        } else {
            // Re-enable the hamburger. Passing 0 clears any previously-set
            // up-indicator so the toggle's own drawer indicator takes over again.
            drawerToggle.setHomeAsUpIndicator(0)
            drawerToggle.setToolbarNavigationClickListener { binding.drawerRoot.open() }
        }
        drawerToggle.syncState()
        binding.toolbar.title = titleFor(view)
        drawerAdapter.setSelected(view)
        binding.homeContent.root.visibility = if (view is ShelfView.Home) View.VISIBLE else View.GONE
        if (view is ShelfView.Home) {
            binding.recycler.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
        }
        updateFab(view)
        binding.refresh.isEnabled = true // Patch 12: keep the refresh layout always
        // enabled so the scanning spinner stays visible on EVERY view — previously
        // it was disabled on non-book views (Authors/Series/Folders/Settings) and a
        // scan started on Folders appeared to "stop" the moment the user navigated
        // away, because the spinner was no longer drawn. The scan job itself was
        // never cancelled; only the spinner vanished. Now the spinner remains on
        // screen until the scan finishes, and pull-to-refresh also works everywhere.

        // Swipe-to-dismiss only on the Currently Reading list
        touchHelper?.attachToRecyclerView(null)
        if (view is ShelfView.Reading) {
            val cb =
                object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
                    override fun onMove(
                        rv: RecyclerView,
                        vh: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder,
                    ) = false

                    override fun onSwiped(
                        viewHolder: RecyclerView.ViewHolder,
                        dir: Int,
                    ) {
                        val pos = viewHolder.bindingAdapterPosition
                        val books = bookAdapter?.currentList ?: return
                        if (pos in books.indices) {
                            val book = books[pos]
                            viewModel.clearCurrentlyReading(book.id)
                            Snackbar
                                .make(binding.root, R.string.option_remove_reading, Snackbar.LENGTH_SHORT)
                                .setAction(R.string.cancel) { bookAdapter?.notifyDataSetChanged() }
                                .show()
                        }
                    }
                }
            touchHelper = ItemTouchHelper(cb).also { it.attachToRecyclerView(binding.recycler) }
        }
        invalidateOptionsMenu()
    }

    // ---------------------------------------------------------------- observers
    private fun setupObservers() {
        viewModel.view.observe(this) { view -> applyView(view) }

        // View mode + column count apply immediately: reconfigure the adapter without refetching.
        viewModel.viewModeGrid.observe(this) { reconfigureAdapter() }
        viewModel.gridColumns.observe(this) { reconfigureAdapter() }

        viewModel.homeContent.observe(this) { content ->
            renderHome(content)
        }

        viewModel.content.observe(this) { items ->
            val view = viewModel.view.value ?: ShelfView.Library
            if (view is ShelfView.Home) return@observe

            if (view is ShelfView.Settings) {
                binding.emptyAction.visibility = View.GONE
                showSettingsView()
                return@observe
            }

            if (isFoldersView(view)) {
                binding.emptyAction.visibility = View.GONE
                showFoldersView()
                return@observe
            }

            if (isPlaceholder(view)) {
                clearDynamicEmptyChildren()
                binding.emptyState.visibility = View.VISIBLE
                binding.emptyState.gravity = android.view.Gravity.CENTER
                binding.recycler.visibility = View.GONE
                binding.emptyIcon.visibility = View.GONE
                binding.emptyText.visibility = View.VISIBLE
                binding.emptyText.text = getString(R.string.empty_placeholder)
                binding.emptyHint.text = getString(R.string.coming_soon)
                binding.emptyHint.visibility = View.VISIBLE
                binding.emptyAction.visibility = View.GONE
                return@observe
            }

            // Book list views: drop any Folders/Settings rows added earlier.
            clearDynamicEmptyChildren()

            if (items.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                binding.emptyState.gravity = android.view.Gravity.CENTER
                binding.recycler.visibility = View.GONE
                binding.emptyIcon.visibility = View.VISIBLE
                binding.emptyText.visibility = View.VISIBLE
                binding.emptyText.text =
                    when (view) {
                        is ShelfView.Reading -> getString(R.string.empty_reading)
                        is ShelfView.Favorites -> getString(R.string.empty_favorites)
                        is ShelfView.AuthorsList -> getString(R.string.empty_authors)
                        is ShelfView.SeriesList -> getString(R.string.empty_series)
                        else -> getString(R.string.empty_library)
                    }
                binding.emptyHint.text = if (view is ShelfView.Library) getString(R.string.empty_library_hint) else ""
                binding.emptyHint.visibility = if (view is ShelfView.Library) View.VISIBLE else View.GONE
                binding.emptyAction.visibility = if (view is ShelfView.Library) View.VISIBLE else View.GONE
                binding.emptyAction.setOnClickListener {
                    openMultiFileLauncher.launch(BookFileTypes.acceptedMimeTypes)
                }
            } else {
                binding.emptyState.visibility = View.GONE
                binding.recycler.visibility = View.VISIBLE
                binding.emptyAction.visibility = View.GONE
            }

            val first = items.firstOrNull()
            if (first is DisplayItem.GroupRow) {
                bindRowAdapter(items.filterIsInstance<DisplayItem.GroupRow>())
            } else {
                bindBookAdapter(items.filterIsInstance<DisplayItem.Book>().map { it.book })
            }
        }

        viewModel.scanning.asLiveData().observe(this) { binding.refresh.isRefreshing = it }
        viewModel.scanMessage.asLiveData().observe(this) { msg ->
            msg?.let {
                Snackbar.make(binding.refresh, it, Snackbar.LENGTH_SHORT).show()
                viewModel.setScanMessage(null)
            }
        }
    }

    private fun bindBookAdapter(books: List<BookEntity>) {
        currentBooks = books
        val grid = viewModel.viewModeGrid.value == true
        val cols = viewModel.gridColumns.value ?: 3

        // Patch 10: when switching shelf views (drawer tap / "Show" action /
        // sort change / grid-column change) the user wants a clean, instant
        // reset to the top — no visible fast-scroll, no mid-screen land, no
        // leftover scroll offset from the previous view. The Patch 9 fix only
        // ran scrollToPosition(0) inside the submitList commit callback, which
        // fires AFTER DiffUtil has already animated item inserts/removes; the
        // user therefore saw the old scroll offset and the item-diff animation
        // for a frame before the jump to 0 landed. Here we take a hard,
        // non-animated reset path: stop any in-flight scroll, disable item
        // animations for the swap, create a FRESH LayoutManager + BookAdapter
        // (so there is no saved scroll state to restore and no diff to animate
        // — the new list is laid out from position 0 from the start), then pin
        // to the top with scrollToPositionWithOffset before submitting.
        if (scrollToTopOnNextContent) {
            scrollToTopOnNextContent = false
            val previousAnimator = binding.recycler.itemAnimator
            binding.recycler.stopScroll()
            binding.recycler.itemAnimator = null

            val newAdapter = BookAdapter(grid, ::openBook, ::showBookOptions, ::openDetails)
            newAdapter.stateRestorationPolicy =
                RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            val lm = if (grid) GridLayoutManager(this, cols) else LinearLayoutManager(this)

            bookAdapter = newAdapter
            rowAdapter = null
            binding.recycler.layoutManager = lm
            binding.recycler.adapter = newAdapter

            (lm as LinearLayoutManager).scrollToPositionWithOffset(0, 0)

            newAdapter.submitList(books) {
                binding.recycler.post {
                    (binding.recycler.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(0, 0)
                    binding.recycler.itemAnimator = previousAnimator
                }
            }
            return
        }

        if (bookAdapter == null || bookAdapter?.grid != grid) {
            bookAdapter = BookAdapter(grid, ::openBook, ::showBookOptions, ::openDetails)
            rowAdapter = null
            binding.recycler.adapter = bookAdapter
        }
        // CRITICAL: keep the LayoutManager in sync with the adapter's mode.
        // Forcing GridLayoutManager here even in list mode left list cards laid
        // out in a 3-column grid (cut off) after returning from the reader.
        // Patch 11: only recreate the LayoutManager when the grid/list mode or
        // column count actually changed. Recreating it on every Room emission
        // wipes scroll state whenever the database re-emits (e.g. after opening a
        // book updates last-read progress), which would defeat scroll restoration.
        // NOTE: GridLayoutManager extends LinearLayoutManager, so we must check
        // for the more specific type first when in list mode, otherwise a
        // leftover GridLayoutManager from a previous grid view would pass an
        // "is LinearLayoutManager" check and never get replaced.
        val currentLm = binding.recycler.layoutManager
        val needsNewLm = if (grid) {
            currentLm !is GridLayoutManager || currentLm.spanCount != cols
        } else {
            currentLm !is LinearLayoutManager || currentLm is GridLayoutManager
        }
        if (needsNewLm) {
            binding.recycler.layoutManager =
                if (grid) GridLayoutManager(this, cols) else LinearLayoutManager(this)
        }
        if (grid) {
            (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = cols
        }
        bookAdapter?.submitList(books) {
            // Patch 11: after the new list is committed, restore the saved scroll
            // position if the user is returning from the reader / details.
            if (pendingRestoreKey != null) {
                binding.recycler.post { tryRestoreScroll() }
            }
        }
    }

    private fun reconfigureAdapter() {
        val grid = viewModel.viewModeGrid.value == true
        val cols = viewModel.gridColumns.value ?: 3
        if (currentBooks.isNotEmpty() && isBookView(viewModel.view.value ?: ShelfView.Library)) {
            // Grid <-> list mode and grid-column-count changes are also a
            // "different layout" event, so they get the same clean top reset
            // as a shelf-view switch (see bindBookAdapter): fresh adapter, no
            // item-diff animation, pinned to position 0 before layout.
            val previousAnimator = binding.recycler.itemAnimator
            binding.recycler.stopScroll()
            binding.recycler.itemAnimator = null

            val newAdapter = BookAdapter(grid, ::openBook, ::showBookOptions, ::openDetails)
            newAdapter.stateRestorationPolicy =
                RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            val lm = if (grid) GridLayoutManager(this, cols) else LinearLayoutManager(this)

            bookAdapter = newAdapter
            binding.recycler.layoutManager = lm
            binding.recycler.adapter = newAdapter
            lm.scrollToPositionWithOffset(0, 0)

            newAdapter.submitList(currentBooks) {
                binding.recycler.post {
                    (binding.recycler.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(0, 0)
                    binding.recycler.itemAnimator = previousAnimator
                }
            }
        }
    }

    private fun bindRowAdapter(rows: List<DisplayItem.GroupRow>) {
        val view = viewModel.view.value
        val icon = if (view is ShelfView.SeriesList) R.drawable.ic_series else R.drawable.ic_person_frame

        // For consistency with the book-list reset path, apply the same hard
        // top reset on row-based (Authors/Series) views: stop scroll, disable
        // item animation, use a FRESH adapter (so switching Authors<->Series
        // never diffs the old rows against the new ones), pin to top before
        // the new list is submitted. This avoids leftover scroll offset and
        // visible fast-scroll when switching to/from Authors/Series.
        if (scrollToTopOnNextContent) {
            scrollToTopOnNextContent = false
            val previousAnimator = binding.recycler.itemAnimator
            binding.recycler.stopScroll()
            binding.recycler.itemAnimator = null

            val newRowAdapter = RowAdapter(icon, onClick = { (name, _) ->
                // Patch 11: capture THIS list's scroll position before descending
                // into the author/series detail, so returning restores it. The
                // detail list itself is NOT one of the capture candidates (it's a
                // different ShelfView type than this list), so it naturally
                // top-resets on the content re-emission below.
                captureScrollState()
                pendingRestoreKey = viewKey(viewModel.view.value ?: ShelfView.Library)
                scrollToTopOnNextContent = true
                viewModel.openDetail(name)
            })
            newRowAdapter.stateRestorationPolicy =
                RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            rowAdapter = newRowAdapter
            bookAdapter = null
            val lm = LinearLayoutManager(this)
            binding.recycler.layoutManager = lm
            binding.recycler.adapter = newRowAdapter
            lm.scrollToPositionWithOffset(0, 0)

            newRowAdapter.submitList(rows.map { com.epubreader.app.data.GroupedRow(it.name, it.count) }) {
                binding.recycler.post {
                    (binding.recycler.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(0, 0)
                    binding.recycler.itemAnimator = previousAnimator
                }
            }
            return
        }

        // Patch 11: tapping an author/series row navigates into its books layout.
        // We must capture the parent list's scroll position BEFORE switching so it
        // can be restored when the user returns via the back arrow / phone back.
        // We deliberately do NOT set scrollToTopOnNextContent here — the detail
        // list itself top-resets via the existing top-reset path in bindBookAdapter
        // (openDetail changes the view → content re-emits → top reset), but the
        // parent list must NOT be top-reset on return.
        val onClick: (Pair<String, Long?>) -> Unit = { pair ->
            captureScrollState()
            pendingRestoreKey = viewKey(viewModel.view.value ?: ShelfView.Library)
            // Patch 11: the newly-opened author/series book list should still start
            // cleanly at the top (this view's prior scroll has been safely captured
            // for its own future restore — top-resetting a DIFFERENT view's list).
            scrollToTopOnNextContent = true
            viewModel.openDetail(pair.first)
        }
        if (rowAdapter == null) {
            rowAdapter = RowAdapter(icon, onClick = onClick)
            bookAdapter = null
            binding.recycler.adapter = rowAdapter
            // Patch 12: GridLayoutManager extends LinearLayoutManager, so a plain
            // `!is LinearLayoutManager` check MISSES a leftover grid layout manager
            // from a previous book-list (grid) view. The Authors/Series list then
            // rendered inside that grid LM, cutting off the list. Force a plain
            // LinearLayoutManager whenever the current one is a grid (or null, or
            // any future non-linear manager).
            val currentLm = binding.recycler.layoutManager
            if (currentLm !is LinearLayoutManager || currentLm is GridLayoutManager) {
                binding.recycler.layoutManager = LinearLayoutManager(this)
            }
        } else {
            rowAdapter?.iconRes = icon
            // Even when reusing the row adapter, make sure we are NOT still on a
            // grid layout manager (e.g. returning from a grid detail view).
            val currentLm = binding.recycler.layoutManager
            if (currentLm !is LinearLayoutManager || currentLm is GridLayoutManager) {
                binding.recycler.layoutManager = LinearLayoutManager(this)
            }
        }
        rowAdapter?.submitList(
            rows.map {
                com.epubreader.app.data
                    .GroupedRow(it.name, it.count)
            },
        ) {
            // Restore the parent list's scroll on return from a detail layout.
            if (pendingRestoreKey != null) {
                binding.recycler.post { tryRestoreScroll() }
            }
        }
    }

    // ---------------------------------------------------------------- actions
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_bookshelf, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val view = viewModel.view.value ?: ShelfView.Library
        val bookView = isBookView(view)
        menu.findItem(R.id.action_view_mode)?.isVisible = bookView
        menu.findItem(R.id.action_sort)?.isVisible = bookView
        menu.findItem(R.id.action_view_mode)?.setIcon(
            if (viewModel.viewModeGrid.value == true) R.drawable.ic_list else R.drawable.ic_grid,
        )
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_search -> {
                launchSearch()
                true
            }

            R.id.action_sort -> {
                showSortDialog()
                true
            }

            R.id.action_view_mode -> {
                showViewModeDialog()
                true
            }

            R.id.action_import -> {
                openMultiFileLauncher.launch(BookFileTypes.acceptedMimeTypes)
                true
            }

            android.R.id.home -> {
                returnToParentList()
                true
            }

            else -> {
                super.onOptionsItemSelected(item)
            }
        }

    // ---------------------------------------------------------------- search (separate screen)
    private fun launchSearch() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    // ---------------------------------------------------------------- sort (field -> asc/desc -> refresh + scroll top)
    private fun showSortDialog() {
        val options =
            listOf(
                getString(R.string.sort_recently_added) to PrefsManager.SortOption.RECENTLY_ADDED,
                getString(R.string.sort_recently_read) to PrefsManager.SortOption.RECENTLY_READ,
                getString(R.string.sort_title) to PrefsManager.SortOption.TITLE,
                getString(R.string.sort_series) to PrefsManager.SortOption.SERIES,
                getString(R.string.sort_author) to PrefsManager.SortOption.AUTHOR,
            )
        val current = options.indexOfFirst { it.second == viewModel.sort.value }
        AlertDialog
            .Builder(this)
            .setTitle(R.string.action_sort)
            .setSingleChoiceItems(
                options.map { it.first }.toTypedArray(),
                if (current < 0) 0 else current
            ) { d, which ->
                d.dismiss()
                showSortDirectionDialog(options[which].first, options[which].second)
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSortDirectionDialog(
        fieldLabel: String,
        fieldKey: String,
    ) {
        val asc = viewModel.sortAscending.value ?: true
        val labels = arrayOf(getString(R.string.sort_ascending), getString(R.string.sort_descending))
        AlertDialog
            .Builder(this)
            .setTitle("$fieldLabel · ${getString(R.string.sort_choose_order)}")
            .setSingleChoiceItems(labels, if (asc) 0 else 1) { d, which ->
                scrollToTopOnNextContent = true
                viewModel.setSort(fieldKey, which == 0)
                d.dismiss()
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- view mode (List / Grid 2 / Grid 3 / Grid 4)
    private fun showViewModeDialog() {
        val grid = viewModel.viewModeGrid.value == true
        val cols = viewModel.gridColumns.value ?: 3
        val labels =
            arrayOf(
                getString(R.string.view_mode_list),
                getString(R.string.view_mode_grid) + " 2",
                getString(R.string.view_mode_grid) + " 3",
                getString(R.string.view_mode_grid) + " 4",
            )
        val checked = if (!grid) 0 else (cols - 1).coerceIn(1, 3)
        AlertDialog
            .Builder(this)
            .setTitle(R.string.action_view_mode)
            .setSingleChoiceItems(labels, checked) { d, which ->
                when (which) {
                    0 -> {
                        viewModel.setViewMode(false)
                    }

                    else -> {
                        viewModel.setViewMode(true)
                        viewModel.setGridColumns(which + 1)
                    }
                }
                d.dismiss()
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- book options
    private fun showBookOptions(book: BookEntity): Boolean {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels += getString(R.string.option_open)
        actions += { openBook(book) }
        labels += getString(R.string.option_details)
        actions += { openDetails(book) }
        labels += if (book.isFavorite) getString(R.string.option_favorite_remove) else getString(R.string.option_favorite_add)
        actions += { viewModel.toggleFavorite(book) }
        if (viewModel.view.value is ShelfView.Reading) {
            labels += getString(R.string.option_remove_reading)
            actions += { viewModel.clearCurrentlyReading(book.id) }
        }
        labels += getString(R.string.option_remove)
        actions += { confirmDeleteBook(book) }
        AlertDialog
            .Builder(this)
            .setTitle(book.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
        return true
    }

    private fun confirmDeleteBook(book: BookEntity) {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.option_remove)
            .setMessage(getString(R.string.option_remove) + ": " + book.title)
            .setPositiveButton(R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    importer.deleteImported(book)
                    BookRepository(applicationContext).deleteBook(book)
                }
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- FAB + folder
    private fun updateFab(view: ShelfView) {
        when (view) {
            is ShelfView.Library -> {
                binding.fabScan.show()
                binding.fabScan.setImageResource(R.drawable.ic_add)
                binding.fabScan.contentDescription = getString(R.string.action_add)
            }
            // Patch 12: the Folders view already shows its action buttons inline
            // (Scan Now / Select Folder / Remove). The bottom FAB here only popped
            // up the SAME options in a dialog — a duplicate the user explicitly does
            // not want. Hide the FAB on Folders so only the inline buttons remain.
            is ShelfView.Folders -> binding.fabScan.hide()
            else -> binding.fabScan.hide()
        }
    }

    private fun onFabClicked() {
        when (val view = viewModel.view.value ?: ShelfView.Library) {
            is ShelfView.Library -> openMultiFileLauncher.launch(BookFileTypes.acceptedMimeTypes)
            is ShelfView.Folders -> showFolderOptionsDialog()
            else -> {}
        }
    }

    private fun showFolderOptionsDialog() {
        val items = arrayOf(
            getString(R.string.folder_select),
            getString(R.string.folder_scan_now),
            getString(R.string.folder_refresh_metadata),
            getString(R.string.folder_remove)
        )

        AlertDialog
            .Builder(this)
            .setTitle(R.string.folder_options)
            .setItems(items) { _, which ->

                when (which) {

                    0 -> {
                        openTreeLauncher.launch(null)
                    }

                    1 -> {
                        rescanSelectedFolder()
                    }

                    2 -> {
                        refreshMetadataSelectedFolder()
                    }

                    3 -> {
                        prefs.selectedFolderUri = null
                        showFoldersEmptyState()

                        Snackbar
                            .make(
                                binding.refresh,
                                R.string.folder_none,
                                Snackbar.LENGTH_SHORT
                            )
                            .show()
                    }
                }
            }
            .setNegativeButton(
                R.string.cancel,
                null
            )
            .show()
    }

    private fun folderDisplayName(uriString: String): String {
        return try {
            val treeUri = Uri.parse(uriString)
            val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            docId.substringAfterLast(':').ifBlank { docId }
        } catch (_: Exception) {
            uriString
        }
    }

    /**
     * Re-parses every EPUB currently in the selected folder for metadata only.
     *
     * This intentionally does NOT use RescanDecision because the whole point
     * of this operation is to re-read metadata even when the file's size and
     * modified timestamp have not changed.
     *
     * The importer updates only metadata columns. Reading state and cached
     * EPUB content remain untouched.
     */

    private fun refreshMetadataSelectedFolder() {

        val uriString = prefs.selectedFolderUri

        if (uriString == null) {
            Snackbar
                .make(
                    binding.root,
                    R.string.scan_no_folder,
                    Snackbar.LENGTH_LONG
                )
                .setAction(R.string.folder_select) {
                    openTreeLauncher.launch(null)
                }
                .show()

            return
        }

        if (scanJob?.isActive == true) {
            Snackbar
                .make(
                    binding.refresh,
                    R.string.scan_already_running,
                    Snackbar.LENGTH_SHORT
                )
                .show()

            return
        }

        val treeUri = Uri.parse(uriString)

        // Match Scan Now's immediate visual feedback, but only for a brief
        // moment. The metadata refresh itself remains a background job and does
        // not keep the spinner visible or restrict navigation.
        viewModel.setScanning(true)
        backResetHandler.postDelayed({
            viewModel.setScanning(false)
        }, 1200L)

        // Metadata refresh is deliberately a silent background job. Unlike the
        // user-facing folder scan, it must not activate the SwipeRefresh spinner
        // or otherwise restrict navigation while it runs. The tracked Job keeps
        // duplicate refreshes from starting, and the completion snackbar appears
        // wherever the user is in the app when the work finishes.
        scanJob =
            lifecycleScope.launch(Dispatchers.IO) {

                try {

                    val files =
                        importer.listEpubFiles(treeUri)

                    val result =
                        importer.refreshMetadata(files)

                    MetadataRefreshReportStore.latest = result

                    withContext(Dispatchers.Main) {

                        val snackbar =
                            Snackbar.make(
                                binding.refresh,
                                getString(
                                    R.string.metadata_refresh_summary,
                                    result.updated,
                                    result.unchanged,
                                    result.skipped,
                                    result.failed,
                                ),
                                Snackbar.LENGTH_LONG
                            )

                        if (result.items.isNotEmpty()) {
                            snackbar.setAction(R.string.scan_show) {
                                startActivity(
                                    Intent(
                                        this@MainActivity,
                                        MetadataRefreshActivity::class.java
                                    )
                                )
                            }
                        }

                        snackbar.show()
                    }

                } catch (_: Exception) {

                    withContext(Dispatchers.Main) {

                        Snackbar
                            .make(
                                binding.refresh,
                                R.string.scan_failed,
                                Snackbar.LENGTH_LONG
                            )
                            .show()
                    }

                } finally {
                    // No scanning-state UI is used for metadata refresh.
                }
            }
    }

    // ---------------------------------------------------------------- scanning
    private fun rescanSelectedFolder() {
        val uriString = prefs.selectedFolderUri
        if (uriString == null) {
            binding.refresh.isRefreshing = false
            Snackbar.make(binding.root, R.string.scan_no_folder, Snackbar.LENGTH_LONG)
                .setAction(R.string.folder_select) { openTreeLauncher.launch(null) }
                .show()
            return
        }
        scanFolder(Uri.parse(uriString), fromRefresh = true)
    }

    /** Scans the selected SAF tree. Re-scans are fast because (a) every epub is
     *  listed in one bulk cursor pass per directory instead of per-file
     *  DocumentFile metadata calls, (b) existing books are matched against an
     *  in-memory fingerprint map (single DB query) and skipped when size + mtime
     *  are unchanged, and (c) all new/changed books are committed in one
     *  transaction so the library list refreshes once at the end instead of
     *  once per book (no visible layout thrash while scanning). */
    private fun scanFolder(treeUri: Uri, fromRefresh: Boolean) {
        // Patch 12: don't start a second scan while one is already running — two
        // concurrent scans on the same importer/DB would conflict and the second
        // would silently fail, which looked like "Scan Now doesn't do anything."
        if (scanJob?.isActive == true) {
            Snackbar.make(binding.refresh, R.string.scan_already_running, Snackbar.LENGTH_SHORT).show()
            return
        }
        try {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        prefs.selectedFolderUri = treeUri.toString()
        viewModel.setScanning(true)
        // Tracked job: lives in the Activity lifecycle scope, which is NOT tied
        // to any one shelf view. Navigating Library -> Settings -> Folders (or
        // anywhere else) does NOT cancel this job, so the scan keeps running and
        // the spinner stays visible until it finishes.
        scanJob = lifecycleScope.launch(Dispatchers.IO) {
            // Patch 12: wrap the whole scan in try/catch/finally so the scanning
            // spinner is ALWAYS cleared — even if listEpubFiles / fingerprint map /
            // commitImports throws. Without this, an exception would leave
            // setScanning(true) forever and the spinner stuck.
            try {
                // (1) One cursor pass per directory — name + size + mtime for every epub.
                val files = importer.listEpubFiles(treeUri)
                // (2) One DB query → in-memory fingerprint map for the fast skip path.
                val fingerprints = importer.sourceFingerprintMap()
                // (3) Prepare (copy + parse + cover) every new/changed book WITHOUT
                //     touching the database. Heavy file IO stays outside the DB lock.
                val prepared = mutableListOf<EpubImporter.PreparedImport>()
                for (file in files) {
                    val matches = fingerprints[file.name].orEmpty()
                    // A stable source URI is the primary identity. Legacy rows with
                    // no source URI are deliberately processed once so their identity
                    // is upgraded; they must never be skipped solely by filename.
                    if (matches.any { fp ->
                            fp.sourceUri == file.uri.toString() &&
                                    RescanDecision.shouldSkip(
                                        existingFileSize = fp.fileSize,
                                        existingMtime = fp.sourceLastModified,
                                        sourceSize = file.size,
                                        sourceMtime = file.lastModified,
                                        cachedFileExists = File(fp.path).exists(),
                                    )
                        }
                    ) continue
                    try {
                        importer.prepareImport(file.uri, file.name, file.size, file.lastModified)
                            ?.let { prepared += it }
                    } catch (_: Exception) {
                    }
                }
                // (4) Commit all new/changed books in ONE transaction so the library's
                //     Room Flow re-emits a single time at the end.
                val newIds = importer.commitImports(prepared)
                withContext(Dispatchers.Main) {
                    showScanResult(newIds)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.refresh, R.string.scan_failed, Snackbar.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    viewModel.setScanning(false)
                }
            }
        }
    }

    private fun importMultiple(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModel.setScanning(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val newIds = mutableListOf<Long>()
            for (uri in uris) {
                try {
                    val result = importer.importUriResult(uri)
                    if (result.isNew && result.bookId != null) newIds.add(result.bookId)
                } catch (_: Exception) {
                }
            }
            withContext(Dispatchers.Main) {
                viewModel.setScanning(false)
                showScanResult(newIds)
            }
        }
    }

    // Patch 16 (Issue #3): now takes the actual ids of the newly imported books
    // (previously just a count) so the "Show" action can open the Recently
    // Added temp screen scoped to exactly those books.
    private fun showScanResult(newIds: List<Long>) {
        val newCount = newIds.size
        val text = if (newCount > 0)
            getString(R.string.scan_complete_new, newCount)
        else
            getString(R.string.scan_complete_none)
        val snackbar = Snackbar.make(binding.refresh, text, Snackbar.LENGTH_LONG)
        if (newCount > 0) {
            snackbar.setAction(R.string.scan_show) {
                // Patch 16 (Issue #3): instead of dumping the user on the Library
                // and making them scroll to find the new book(s), remember the
                // shelf they're currently on and open the transient Recently
                // Added screen scoped to just the newly imported ids. The screen
                // is destroyed (state discarded) on back — see exitRecentlyAdded().
                viewBeforeRecentlyAdded = viewModel.view.value ?: ShelfView.Library
                scrollToTopOnNextContent = true
                viewModel.setView(ShelfView.RecentlyAdded(newIds))
            }
        }
        snackbar.show()
    }

    private fun showFoldersEmptyState() {
        showFoldersView()
    }

    private fun showFoldersView() {
        clearDynamicEmptyChildren()
        binding.recycler.visibility = View.GONE
        // Patch 11: start the Folder view at the TOP of the layout instead of
        // vertically centered in the middle of the screen.
        binding.emptyState.gravity = android.view.Gravity.START or android.view.Gravity.TOP
        binding.emptyState.visibility = View.VISIBLE
        binding.emptyIcon.visibility = View.GONE
        val uri = prefs.selectedFolderUri
        binding.emptyText.visibility = View.VISIBLE
        binding.emptyText.text = if (uri != null) {
            getString(R.string.folder_selected, folderDisplayName(uri))
        } else {
            getString(R.string.folder_none)
        }
        binding.emptyHint.text = getString(R.string.folder_hint)
        binding.emptyHint.visibility = View.VISIBLE

        if (uri != null) {

            addDynamicButton(R.string.folder_scan_now) {
                rescanSelectedFolder()
            }

            addDynamicButton(R.string.folder_refresh_metadata) {
                refreshMetadataSelectedFolder()
            }

            addDynamicButton(R.string.folder_select) {
                openTreeLauncher.launch(null)
            }

            addDynamicButton(R.string.folder_remove) {
                prefs.selectedFolderUri = null
                showFoldersView()
                Snackbar.make(binding.refresh, R.string.folder_none, Snackbar.LENGTH_SHORT).show()
            }
        } else {
            addDynamicButton(R.string.folder_select) { openTreeLauncher.launch(null) }
        }
    }

    private fun showSettingsView() {
        clearDynamicEmptyChildren()
        binding.recycler.visibility = View.GONE
        // Patch 11: start the Settings view at the TOP of the layout instead of
        // vertically centered in the middle of the screen.
        binding.emptyState.gravity = android.view.Gravity.START or android.view.Gravity.TOP
        binding.emptyState.visibility = View.VISIBLE
        binding.emptyIcon.visibility = View.GONE
        // Patch 11: the top toolbar already shows the view name ("Settings"),
        // so a second in-screen "Settings" heading was redundant — removed.
        binding.emptyText.visibility = View.GONE
        binding.emptyHint.visibility = View.GONE

        val folder = prefs.selectedFolderUri
        addSettingsRow(
            getString(R.string.settings_scanned_folder),
            if (folder != null) folderDisplayName(folder) else getString(R.string.folder_none)
        )
        addSettingsRow(getString(R.string.settings_reader_theme), readerThemeLabel())
        addSettingsRow(getString(R.string.settings_about), getString(R.string.settings_about_detail))
        // Patch 11: app-level "Screen On" toggle. When on, keeps the screen awake
        // for 10 minutes longer than the system screen-off timeout while the app
        // is in the foreground (uses FLAG_KEEP_SCREEN_ON + a 10-minute countdown
        // that releases the flag so the system's normal timeout takes over again).
        addScreenOnToggle()
    }

    // Patch 17 (Addition #1): theme display name comes from the single-source
    // registry so the main-app settings row stays in sync with the reader
    // dropdown automatically.
    private fun readerThemeLabel(): String =
        getString(com.epubreader.app.ui.ReaderTheme.byId(prefs.theme).displayNameRes)

    private fun addSettingsRow(label: String, value: String) {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 12.dp(), 0, 0)
        }
        android.widget.TextView(this).apply {
            text = label
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            textSize = 12f
            row.addView(this)
        }
        android.widget.TextView(this).apply {
            text = value
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            textSize = 15f
            row.addView(this)
        }
        binding.emptyState.addView(row)
        dynamicEmptyChildren.add(row)
    }

    // ------------------------------------------------------------- Screen On (Patch 11)
    // See util/KeepScreenOnController.kt for the full write-up. It is shared by
    // MainActivity, ReaderActivity and BookDetailsActivity so "keep the screen
    // on 10 minutes longer" applies app-wide, not just on the bookshelf.
    private fun addScreenOnToggle() {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 12.dp(), 0, 0)
            // Fill the available width so the label column (weight 1f) expands and
            // the switch hugs the trailing edge cleanly.
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val labelColumn = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { weight = 1f }
        }
        android.widget.TextView(this).apply {
            text = getString(R.string.settings_keep_screen_on)
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            textSize = 15f
            labelColumn.addView(this)
        }
        android.widget.TextView(this).apply {
            text = getString(R.string.settings_keep_screen_on_summary)
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            textSize = 12f
            labelColumn.addView(this)
        }
        // Material2 (Theme.MaterialComponents) is used, so SwitchCompat is
        // required for proper theming — MaterialSwitch would not theme well.
        val switch = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = prefs.keepScreenOn
        }
        switch.setOnCheckedChangeListener { _, isChecked ->
            prefs.keepScreenOn = isChecked
            keepScreenOnController.refresh()
        }
        row.addView(labelColumn)
        row.addView(switch)
        binding.emptyState.addView(row)
        dynamicEmptyChildren.add(row)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        keepScreenOnController.bump()
    }

    private fun themeColor(attr: Int): Int {
        // Resolve a theme color attribute to a concrete ARGB int. Use
        // obtainStyledAttributes (not TypedValue.data) because textColorPrimary /
        // textColorSecondary are ColorStateList attrs whose .data can be wrong.
        val ta = obtainStyledAttributes(intArrayOf(attr))
        try {
            return ta.getColor(0, 0xFF000000.toInt())
        } finally {
            ta.recycle()
        }
    }

    private fun addDynamicButton(labelRes: Int, onClick: () -> Unit) {
        val btn = com.google.android.material.button.MaterialButton(this)
        btn.text = getString(labelRes)
        val lp = androidx.recyclerview.widget.RecyclerView.LayoutParams(
            androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT,
            androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = 8.dp()
        btn.layoutParams = lp
        btn.setOnClickListener { onClick() }
        binding.emptyState.addView(btn)
        dynamicEmptyChildren.add(btn)
    }

    private fun clearDynamicEmptyChildren() {
        dynamicEmptyChildren.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        dynamicEmptyChildren.clear()
    }

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).toInt()

    private val dynamicEmptyChildren = mutableListOf<android.view.View>()

    /**
     * Patch 18 (Addition #3): handles an incoming ACTION_VIEW intent for an
     * .epub (from the system "Open with" sheet / a file manager). Validates
     * the file type client-side (defense in depth — the intent filter already
     * scopes to epub MIME + .epub path), imports it, and opens the reader.
     */
    private fun handleViewIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (!BookFileTypes.isBookFile(uri, contentResolver)) {
            Snackbar.make(binding.fabScan, R.string.import_unsupported_file, Snackbar.LENGTH_SHORT).show()
            return
        }
        viewModel.setScanning(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val bookId = try {
                importer.importUri(uri)
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                viewModel.setScanning(false)
                if (bookId != null) {
                    startActivity(
                        Intent(this@MainActivity, ReaderActivity::class.java)
                            .putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                } else {
                    Snackbar.make(binding.fabScan, R.string.import_failed, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openBook(book: BookEntity) {
        // Patch 11: capture this view's scroll position before navigating away so
        // it can be restored exactly on return. Reading is excluded (always
        // refreshes from top on return, per Patch 10 behaviour for that view).
        captureScrollState(book.id)
        viewModel.view.value?.let {
            if (it is ShelfView.Reading) {
                pendingReadingTopReset = true
            } else if (isRestoreEligible(it)) {
                pendingRestoreKey = viewKey(it)
            }
        }
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id))
    }

    private fun openDetails(book: BookEntity) {
        captureScrollState(book.id)
        viewModel.view.value?.let {
            if (it is ShelfView.Reading) {
                pendingReadingTopReset = true
            } else if (isRestoreEligible(it)) {
                pendingRestoreKey = viewKey(it)
            }
        }
        startActivity(
            Intent(this, BookDetailsActivity::class.java).putExtra(
                BookDetailsActivity.EXTRA_BOOK_ID,
                book.id
            )
        )
    }
}
