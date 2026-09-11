package com.epubreader.app

import com.epubreader.app.data.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeOrderingTest {
    @Test
    fun recentlyAddedUsesNewestFirstWithStableIdTieBreak() {
        val older = BookEntity(id = 1, title = "Older", author = "A", path = "old", checksum = "1", addedDate = 100L)
        val newer = BookEntity(id = 2, title = "Newer", author = "B", path = "new", checksum = "2", addedDate = 200L)
        val sameTimeHigherId = BookEntity(id = 3, title = "Same time", author = "C", path = "same", checksum = "3", addedDate = 200L)

        val result = listOf(older, newer, sameTimeHigherId).sortedWith(
            compareByDescending<BookEntity> { it.addedDate }.thenByDescending { it.id }
        )

        assertEquals(listOf(3L, 2L, 1L), result.map { it.id })
    }
}
