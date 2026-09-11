package com.epubreader.app

import com.epubreader.app.data.BookEntity
import com.epubreader.app.data.PrefsManager
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeOrderingTest {

    private fun applySort(list: List<BookEntity>, sort: String, asc: Boolean): List<BookEntity> {
        // Mirror of BookshelfViewModel.applySort for RECENTLY_ADDED.
        val sorted = when (sort) {
            PrefsManager.SortOption.RECENTLY_ADDED ->
                if (asc) {
                    list.sortedWith(compareBy<BookEntity> { it.addedDate }.thenBy { it.id })
                } else {
                    list.sortedWith(compareByDescending<BookEntity> { it.addedDate }.thenByDescending { it.id })
                }
            else -> list.sortedWith(compareBy<BookEntity> { it.sortTitle })
        }
        return if (sort == PrefsManager.SortOption.RECENTLY_ADDED) sorted else if (asc) sorted else sorted.reversed()
    }

    @Test
    fun recentlyAddedUsesNewestFirstWithStableIdTieBreak() {
        val older = BookEntity(id = 1, title = "Older", author = "A", path = "old", checksum = "1", addedDate = 100L)
        val newer = BookEntity(id = 2, title = "Newer", author = "B", path = "new", checksum = "2", addedDate = 200L)
        val sameTimeHigherId = BookEntity(id = 3, title = "Same time", author = "C", path = "same", checksum = "3", addedDate = 200L)

        val result = applySort(listOf(older, newer, sameTimeHigherId), PrefsManager.SortOption.RECENTLY_ADDED, false)

        assertEquals(listOf(3L, 2L, 1L), result.map { it.id })
    }

    @Test
    fun recentlyAddedAscendingGivesOldestFirst() {
        val older = BookEntity(id = 1, title = "Older", author = "A", path = "old", checksum = "1", addedDate = 100L)
        val newer = BookEntity(id = 2, title = "Newer", author = "B", path = "new", checksum = "2", addedDate = 200L)

        val result = applySort(listOf(newer, older), PrefsManager.SortOption.RECENTLY_ADDED, true)

        assertEquals(listOf(1L, 2L), result.map { it.id })
    }

    @Test
    fun recentlyAddedAscendingTieBreakById() {
        val first = BookEntity(id = 5, title = "First", author = "A", path = "f", checksum = "1", addedDate = 300L)
        val second = BookEntity(id = 3, title = "Second", author = "B", path = "s", checksum = "2", addedDate = 300L)

        val result = applySort(listOf(first, second), PrefsManager.SortOption.RECENTLY_ADDED, true)

        // Same addedDate → ascending tie-break by id: 3 before 5
        assertEquals(listOf(3L, 5L), result.map { it.id })
    }
}
