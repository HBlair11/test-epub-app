package com.epubreader.app.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.epubreader.app.data.BookEntity
import com.epubreader.app.data.BookRepository
import com.epubreader.app.data.GroupedRow
import com.epubreader.app.data.PrefsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed class DisplayItem {
    data class Book(val book: BookEntity) : DisplayItem()
    data class GroupRow(val name: String, val count: Int, val isSeries: Boolean) : DisplayItem()
}

sealed class ShelfView {
    object Reading : ShelfView()
    object Library : ShelfView()
    object Favorites : ShelfView()
    object Finished : ShelfView()
    object ToBeRead : ShelfView()
    object AuthorsList : ShelfView()
    object SeriesList : ShelfView()
    object Collections : ShelfView()
    object Folders : ShelfView()
    object Settings : ShelfView()

    // Patch 16 (Issue #3): a transient, non-persisted view that shows only the
    // books imported in the most recent scan. It is entered by tapping "Show" on
    // the scan-complete snackbar and exited via the back button / toolbar back
    // arrow, which restores the view the user was on before the scan.
    data class RecentlyAdded(val ids: List<Long>) : ShelfView()
    data class AuthorDetail(val name: String) : ShelfView()
    data class SeriesDetail(val name: String) : ShelfView()
}

class BookshelfViewModel(
    private val repo: BookRepository,
    private val prefs: PrefsManager
) : ViewModel() {

    val viewModeGrid: MutableLiveData<Boolean> = MutableLiveData(prefs.viewModeGrid)
    val gridColumns: MutableLiveData<Int> = MutableLiveData(prefs.gridColumns)
    val sort: MutableLiveData<String> = MutableLiveData(defaultSortFor(initialView()).first)
    val sortAscending: MutableLiveData<Boolean> = MutableLiveData(defaultSortFor(initialView()).second)

    /** Per-view sort held in memory only — never persisted, reset to defaults
     *  on every cold start. null = use the view's natural default. */
    private val sessionSorts = mutableMapOf<String, Pair<String, Boolean>>()

    private val _view = MutableLiveData<ShelfView>(initialView())
    val view: LiveData<ShelfView> = _view

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _scanMessage = MutableStateFlow<String?>(null)
    val scanMessage: StateFlow<String?> = _scanMessage.asStateFlow()

    private fun initialView(): ShelfView {
        val key = prefs.lastView
        return when (key) {
            KEY_READING -> ShelfView.Reading
            KEY_FAVORITES -> ShelfView.Favorites
            KEY_AUTHORS -> ShelfView.AuthorsList
            KEY_SERIES -> ShelfView.SeriesList
            // Finished / To Be Read were removed from the drawer; fall back to
            // Library so a stale persisted view never lands on a hidden section.
            KEY_FINISHED, KEY_TBR -> ShelfView.Library
            else -> ShelfView.Library
        }
    }

    private data class Trigger(val view: ShelfView, val sort: String, val asc: Boolean)

    private val _refresh = MutableLiveData<Unit>(Unit)

    private val _trigger = MediatorLiveData<Trigger>().apply {
        addSource(_view) { v ->
            value = Trigger(v, sort.value ?: defaultSortFor(v).first, sortAscending.value ?: defaultSortFor(v).second)
        }
        addSource(_refresh) {
            val v = _view.value ?: initialView()
            value = Trigger(v, sort.value ?: defaultSortFor(v).first, sortAscending.value ?: defaultSortFor(v).second)
        }
        value = Trigger(
            initialView(),
            sort.value ?: defaultSortFor(initialView()).first,
            sortAscending.value ?: defaultSortFor(initialView()).second
        )
    }

    val content: LiveData<List<DisplayItem>> = _trigger.switchMap { t ->
        flowFor(t.view, t.sort, t.asc).asLiveData()
    }

    private fun flowFor(view: ShelfView, sort: String, asc: Boolean) = when (view) {
        is ShelfView.Reading ->
            repo.observeCurrentlyReading().map { applySort(it, sort, asc).map { DisplayItem.Book(it) } }

        is ShelfView.Library ->
            repo.observeBooks().map { applySort(it, sort, asc).map { DisplayItem.Book(it) } }

        is ShelfView.Favorites ->
            repo.observeFavorites().map { applySort(it, sort, asc).map { DisplayItem.Book(it) } }

        is ShelfView.Finished ->
            repo.observeFinished().map { applySort(it, sort, asc).map { DisplayItem.Book(it) } }

        is ShelfView.ToBeRead ->
            repo.observeToBeRead().map { applySort(it, sort, asc).map { DisplayItem.Book(it) } }

        is ShelfView.RecentlyAdded ->
            // Patch 16 (Issue #3): the temp screen ignores the sort selector and
            // always shows newest-import first (the DAO query already orders by
            // added_date DESC); the user's chosen sort is still applied on top so
            // tapping the sort chip behaves consistently with other shelves.
            repo.observeByIds(view.ids).map { applySort(it, sort, asc).map { DisplayItem.Book(it) } }

        is ShelfView.AuthorsList ->
            repo.observeAuthors().map { it.map { DisplayItem.GroupRow(it.name, it.count, false) } }

        is ShelfView.SeriesList ->
            repo.observeSeries().map { it.map { DisplayItem.GroupRow(it.name, it.count, true) } }

        is ShelfView.AuthorDetail ->
            repo.observeByAuthor(view.name).map { applySort(it, sort, asc).map { DisplayItem.Book(it) } }

        is ShelfView.SeriesDetail ->
            repo.observeBySeries(view.name).map { applySort(it, sort, asc).map { DisplayItem.Book(it) } }

        is ShelfView.Collections, is ShelfView.Folders, is ShelfView.Settings ->
            kotlinx.coroutines.flow.flowOf(emptyList<DisplayItem>())
    }

    fun setView(view: ShelfView) {
        // Patch 16 (Issue #3): RecentlyAdded is a transient view — never persist
        // it as the last-opened shelf, otherwise a relaunch would land on an empty
        // (stale id set) screen instead of the user's Library. Persist the
        // underlying shelf for every other view.
        if (view !is ShelfView.RecentlyAdded) prefs.lastView = view.key()
        applySortFor(view)
        _view.value = view
    }

    fun setViewMode(grid: Boolean) {
        viewModeGrid.value = grid; prefs.viewModeGrid = grid
    }

    fun setGridColumns(n: Int) {
        gridColumns.value = n; prefs.gridColumns = n
    }

    fun setSort(option: String, asc: Boolean) {
        val v = _view.value ?: initialView()
        sessionSorts[sortKeyFor(v)] = option to asc
        sort.value = option
        sortAscending.value = asc
        _refresh.value = Unit
    }

    fun openDetail(name: String) {
        val v = _view.value
        val detail = when (v) {
            is ShelfView.AuthorsList -> ShelfView.AuthorDetail(name)
            is ShelfView.SeriesList -> ShelfView.SeriesDetail(name)
            else -> v ?: initialView()
        }
        applySortFor(detail)
        _view.value = detail
    }

    fun isDetailOpen(): Boolean = _view.value is ShelfView.AuthorDetail || _view.value is ShelfView.SeriesDetail
    fun clearDetail() {
        val v = _view.value
        val parent = when (v) {
            is ShelfView.AuthorDetail -> ShelfView.AuthorsList
            is ShelfView.SeriesDetail -> ShelfView.SeriesList
            else -> v ?: initialView()
        }
        applySortFor(parent)
        _view.value = parent
    }

    /** Default sort for a view: Recently Opened for Currently Reading, Title A→Z
     *  everywhere else — EXCEPT the Patch 16 "Recently Added" temp screen, which
     *  defaults to newest-added first (descending) so the just-imported book is
     *  at the top. Sort is session-only and never persisted. */
    private fun defaultSortFor(view: ShelfView): Pair<String, Boolean> =
        when (view) {
            is ShelfView.Reading -> PrefsManager.SortOption.RECENTLY_READ to false
            is ShelfView.RecentlyAdded -> PrefsManager.SortOption.RECENTLY_ADDED to false
            else -> PrefsManager.SortOption.TITLE to true
        }

    private fun sortKeyFor(view: ShelfView): String = when (view) {
        is ShelfView.AuthorDetail -> "author:${view.name}"
        is ShelfView.SeriesDetail -> "series:${view.name}"
        else -> view.key()
    }

    private fun applySortFor(view: ShelfView) {
        val (s, a) = sessionSorts[sortKeyFor(view)] ?: defaultSortFor(view)
        sort.value = s
        sortAscending.value = a
    }

    fun clearCurrentlyReading(bookId: Long) {
        viewModelScope.launch { repo.clearCurrentlyReading(bookId) }
    }

    fun toggleFavorite(book: BookEntity) {
        viewModelScope.launch { repo.setFavorite(book.id, !book.isFavorite) }
    }

    fun setScanning(v: Boolean) {
        _scanning.value = v
    }

    fun setScanMessage(msg: String?) {
        _scanMessage.value = msg
    }

    private fun applySort(list: List<BookEntity>, sort: String, asc: Boolean): List<BookEntity> {
        val sorted = when (sort) {
            PrefsManager.SortOption.RECENTLY_ADDED ->
                list.sortedWith(compareBy<BookEntity> { it.addedDate }.thenBy { it.sortTitle })

            PrefsManager.SortOption.RECENTLY_READ ->
                list.sortedWith(compareBy<BookEntity> { it.lastOpenedDate ?: 0L }.thenBy { it.sortTitle })

            PrefsManager.SortOption.TITLE ->
                list.sortedWith(compareBy<BookEntity> { it.sortTitle }.thenBy { it.sortAuthor })

            PrefsManager.SortOption.AUTHOR ->
                list.sortedWith(compareBy<BookEntity> { it.sortAuthor }.thenBy { it.sortTitle })

            PrefsManager.SortOption.SERIES ->
                list.sortedWith(compareBy<BookEntity> { it.series ?: "\uFFFF" }.thenBy {
                    it.seriesIndex ?: Double.MAX_VALUE
                }.thenBy { it.sortTitle })

            else ->
                list.sortedWith(compareBy<BookEntity> { it.sortTitle }.thenBy { it.sortAuthor })
        }
        return if (asc) sorted else sorted.reversed()
    }

    private fun ShelfView.key(): String = when (this) {
        is ShelfView.Reading -> KEY_READING
        is ShelfView.Library -> KEY_LIBRARY
        is ShelfView.Favorites -> KEY_FAVORITES
        is ShelfView.Finished -> KEY_FINISHED
        is ShelfView.ToBeRead -> KEY_TBR
        is ShelfView.RecentlyAdded -> KEY_RECENTLY_ADDED
        is ShelfView.AuthorsList -> KEY_AUTHORS
        is ShelfView.SeriesList -> KEY_SERIES
        else -> KEY_LIBRARY
    }

    class Factory(private val repo: BookRepository, private val prefs: PrefsManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BookshelfViewModel(repo, prefs) as T
    }

    companion object {
        const val KEY_READING = "reading"
        const val KEY_LIBRARY = "library"
        const val KEY_FAVORITES = "favorites"
        const val KEY_FINISHED = "finished"
        const val KEY_TBR = "tbr"
        const val KEY_RECENTLY_ADDED = "recently_added"
        const val KEY_AUTHORS = "authors"
        const val KEY_SERIES = "series"
    }
}
