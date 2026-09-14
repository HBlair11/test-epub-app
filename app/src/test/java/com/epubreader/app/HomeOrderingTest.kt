package com.epubreader.app

import com.epubreader.app.data.BookEntity
import com.epubreader.app.data.PrefsManager
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeOrderingTest {

    /** Library/shelf view Modified sort: sourceLastModified only, with id as a stable
     * tie-breaker in the same direction as the primary key. */
    private fun applySort(list: List<BookEntity>, sort: String, asc: Boolean): List<BookEntity> {
        val sorted = when (sort) {
            PrefsManager.SortOption.RECENTLY_ADDED ->
                if (asc) {
                    list.sortedWith(compareBy<BookEntity> { it.sourceLastModified }.thenBy { it.id })
                } else {
                    list.sortedWith(compareByDescending<BookEntity> { it.sourceLastModified }.thenByDescending { it.id })
                }
            else -> list.sortedWith(compareBy<BookEntity> { it.sortTitle })
        }
        return if (sort == PrefsManager.SortOption.RECENTLY_ADDED) sorted else if (asc) sorted else sorted.reversed()
    }

    @Test
    fun libraryModifiedNewerFirstUsesSourceLastModifiedAndIgnoresAddedDate() {
        val newerFileOlderImport = BookEntity(id = 1, title = "Newer file", author = "A", path = "new", checksum = "1", addedDate = 10L, sourceLastModified = 2000L)
        val olderFileNewerImport = BookEntity(id = 2, title = "Older file", author = "B", path = "old", checksum = "2", addedDate = 9999L, sourceLastModified = 1000L)

        val result = applySort(
            listOf(newerFileOlderImport, olderFileNewerImport),
            PrefsManager.SortOption.RECENTLY_ADDED,
            true,
        )

        assertEquals(listOf(1L, 2L), result.map { it.id })
    }

    /** Home Recent sort: sourceLastModified DESC, then id DESC. */
    private fun homeSort(list: List<BookEntity>): List<BookEntity> {
        return list.sortedWith(
            compareByDescending<BookEntity> { it.sourceLastModified }
                .thenByDescending { it.id }
        )
    }

    @Test
    fun libraryModifiedOlderFirstUsesSourceLastModifiedAscending() {
        val older = BookEntity(id = 1, title = "Older", author = "A", path = "old", checksum = "1", addedDate = 100L, sourceLastModified = 1000L)
        val newer = BookEntity(id = 2, title = "Newer", author = "B", path = "new", checksum = "2", addedDate = 200L, sourceLastModified = 2000L)

        val result = applySort(listOf(older, newer), PrefsManager.SortOption.RECENTLY_ADDED, false)

        assertEquals(listOf(1L, 2L), result.map { it.id })
    }

    @Test
    fun homeRecentUsesMtimeDescendingThenId() {
        val older = BookEntity(id = 1, title = "Older", author = "A", path = "old", checksum = "1", addedDate = 100L, sourceLastModified = 1000L)
        val newer = BookEntity(id = 2, title = "Newer", author = "B", path = "new", checksum = "2", addedDate = 200L, sourceLastModified = 2000L)
        val sameTimeHigherId = BookEntity(id = 3, title = "Same time", author = "C", path = "same", checksum = "3", addedDate = 200L, sourceLastModified = 2000L)

        val result = homeSort(listOf(older, newer, sameTimeHigherId))

        // Same m-time → stable tie-break by id DESC; addedDate is irrelevant.
        assertEquals(listOf(3L, 2L, 1L), result.map { it.id })
    }

    @Test
    fun homeRecentAscendingGivesOldestMtimeFirst() {
        val older = BookEntity(id = 1, title = "Older", author = "A", path = "old", checksum = "1", addedDate = 100L, sourceLastModified = 1000L)
        val newer = BookEntity(id = 2, title = "Newer", author = "B", path = "new", checksum = "2", addedDate = 200L, sourceLastModified = 2000L)

        val asc = homeSort(listOf(newer, older)).reversed()

        assertEquals(listOf(1L, 2L), asc.map { it.id })
    }
}
