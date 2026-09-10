package com.epubreader.app.ui

import com.epubreader.app.data.BookEntity

/**
 * Data prepared for the dedicated Home screen. Home is intentionally a curated
 * reading surface; the full, sortable library remains on ShelfView.Library.
 */
data class HomeGroup(
    val name: String,
    val count: Int,
    val books: List<BookEntity>,
)

data class HomeContent(
    val continueReading: BookEntity?,
    val recentlyAdded: List<BookEntity>,
    val favorites: List<BookEntity>,
    val topAuthors: List<HomeGroup>,
    val topSeries: List<HomeGroup>,
    val hasBooks: Boolean,
)
