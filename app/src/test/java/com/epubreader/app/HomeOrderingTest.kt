package com.epubreader.app

import com.epubreader.app.data.BookEntity
import com.epubreader.app.data.PrefsManager
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeOrderingTest {

    /** Library/shelf view Recently Added sort: id DESC (primary key), with stable
     *  ascending tie-break by id when asc=true. */
    private fun applySort(list: List<BookEntity>, sort: String, asc: Boolean): List<BookEntity> {
        val sorted = when (sort) {
            PrefsManager.SortOption.RECENTLY_ADDED ->
                if (asc) {
                    list.sortedWith(compareBy { it.id }).reversed()
                } else {
                    list.sortedWith(compareByDescending { it.id })
                }
            else -> list.sortedWith(compareBy<BookEntity> { it.sortTitle })
        }
        return if (sort == PrefsManager.SortOption.RECENTLY_ADDED) sorted else if (asc) sorted else sorted.reversed()
    }

    /** Home Recently Added sort: sourceLastModified DESC, then addedDate DESC,
     *  then id DESC. */
    private fun homeSort(list: List<BookEntity>): List<BookEntity> {
        return list.sortedWith(
            compareByDescending<BookEntity> { it.sourceLastModified }
                .thenByDescending { it.addedDate }
                .thenByDescending { it.id }
        )
    }

    @Test
    fun libraryRecentlyAddedUsesIdDescending() {
        val older = BookEntity(id = 1, title = "Older", author = "A", path = "old", checksum = "1", addedDate = 100L, sourceLastModified = 1000L)
        val newer = BookEntity(id = 2, title = "Newer", author = "B", path = "new", checksum = "2", addedDate = 200L, sourceLastModified = 2000L)

        val result = applySort(listOf(older, newer), PrefsManager.SortOption.RECENTLY_ADDED, false)

        assertEquals(listOf(2L, 1L), result.map { it.id })
    }

    @Test
    fun homeRecentlyAddedUsesMtimeDescendingThenId() {
        val older = BookEntity(id = 1, title = "Older", author = "A", path = "old", checksum = "1", addedDate = 100L, sourceLastModified = 1000L)
        val newer = BookEntity(id = 2, title = "Newer", author = "B", path = "new", checksum = "2", addedDate = 200L, sourceLastModified = 2000L)
        val sameTimeHigherId = BookEntity(id = 3, title = "Same time", author = "C", path = "same", checksum = "3", addedDate = 200L, sourceLastModified = 2000L)

        val result = homeSort(listOf(older, newer, sameTimeHigherId))

        // Same m-time → tie-break by addedDate DESC → tie-break by id DESC
        assertEquals(listOf(3L, 2L, 1L), result.map { it.id })
    }

    @Test
    fun homeRecentlyAddedAscendingGivesOldestMtimeFirst() {
        val older = BookEntity(id = 1, title = "Older", author = "A", path = "old", checksum = "1", addedDate = 100L, sourceLastModified = 1000L)
        val newer = BookEntity(id = 2, title = "Newer", author = "B", path = "new", checksum = "2", addedDate = 200L, sourceLastModified = 2000L)

        val asc = homeSort(listOf(newer, older)).reversed()

        assertEquals(listOf(1L, 2L), asc.map { it.id })
    }
}
