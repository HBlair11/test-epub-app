package com.epubreader.app.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPageMappingTest {

    @Test
    fun `prefix sums count pages before each spine item`() {
        val counts = intArrayOf(3, 5, 2, 4)
        val prefix = ReaderPageMapping.prefixSums(counts)
        // before item 0 -> 0, before item 1 -> 3, before item 2 -> 8, before item 3 -> 10
        assertEquals(listOf(0, 3, 8, 10), prefix.toList())
    }

    @Test
    fun `total pages sums all counts`() {
        assertEquals(14, ReaderPageMapping.totalPages(intArrayOf(3, 5, 2, 4)))
        assertEquals(0, ReaderPageMapping.totalPages(intArrayOf()))
    }

    @Test
    fun `unmeasured entries are treated as one page`() {
        val counts = intArrayOf(-1, 3, -1)
        val prefix = ReaderPageMapping.prefixSums(counts)
        assertEquals(listOf(0, 1, 4), prefix.toList())
        assertEquals(5, ReaderPageMapping.totalPages(counts))
    }

    @Test
    fun `absolute page is prefix plus page in spine`() {
        val counts = intArrayOf(3, 5, 2, 4)
        val prefix = ReaderPageMapping.prefixSums(counts)
        // spine 1, page 2 -> 3 + 2 = 5
        assertEquals(5, ReaderPageMapping.absolutePage(prefix, 1, 2))
        // spine 0, page 0 -> 0
        assertEquals(0, ReaderPageMapping.absolutePage(prefix, 0, 0))
        // out of range spine -> 0
        assertEquals(0, ReaderPageMapping.absolutePage(prefix, 99, 0))
    }

    @Test
    fun `spine and page resolve from absolute page`() {
        val counts = intArrayOf(3, 5, 2, 4)
        // page 5 -> spine 1, page 2 (3 + 2)
        assertEquals(1 to 2, ReaderPageMapping.spineAndPageFor(counts, 5))
        // first page -> spine 0, page 0
        assertEquals(0 to 0, ReaderPageMapping.spineAndPageFor(counts, 0))
        // last page (13) -> spine 3, page 3
        assertEquals(3 to 3, ReaderPageMapping.spineAndPageFor(counts, 13))
    }

    @Test
    fun `out of range absolute clamps to last page`() {
        val counts = intArrayOf(3, 5, 2, 4)
        // 999 is past the end -> last spine (3), last page (3)
        assertEquals(3 to 3, ReaderPageMapping.spineAndPageFor(counts, 999))
    }

    @Test
    fun `round trip absolute to spine page and back`() {
        val counts = intArrayOf(3, 5, 2, 4)
        val prefix = ReaderPageMapping.prefixSums(counts)
        for (absolute in 0 until ReaderPageMapping.totalPages(counts)) {
            val (spine, page) = ReaderPageMapping.spineAndPageFor(counts, absolute)
            assertEquals(absolute, ReaderPageMapping.absolutePage(prefix, spine, page))
        }
    }
}
