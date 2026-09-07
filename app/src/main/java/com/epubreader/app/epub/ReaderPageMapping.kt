package com.epubreader.app.epub

/**
 * Pure (Android-free) absolute-page mapping for the reader timeline.
 *
 * The reader paginates each spine item into N columns ("pages"). The bottom
 * timeline seeker operates on the whole book, so it needs to translate between
 * an absolute page index (0..total-1) and a (spineIndex, pageInSpine) pair.
 *
 * This object holds no state and depends only on the JVM, so it can be unit
 * tested without an Android device or emulator.
 */
object ReaderPageMapping {

    /** Cumulative page count before each spine item: prefix[i] = sum of pages
     *  in spine items 0..i-1. Unmeasured entries (< 1) are treated as 1 page. */
    fun prefixSums(pageCounts: IntArray): IntArray {
        val out = IntArray(pageCounts.size)
        var acc = 0
        for (i in pageCounts.indices) {
            out[i] = acc
            acc += pageCounts[i].coerceAtLeast(1)
        }
        return out
    }

    /** Total pages across the whole book. */
    fun totalPages(pageCounts: IntArray): Int =
        if (pageCounts.isEmpty()) 0 else pageCounts.sumOf { it.coerceAtLeast(1) }

    /** Absolute (0-indexed) page for a (spine, pageInSpine) pair. */
    fun absolutePage(prefixSums: IntArray, spine: Int, pageInSpine: Int): Int {
        if (spine !in prefixSums.indices) return 0
        return prefixSums[spine] + pageInSpine.coerceAtLeast(0)
    }

    /** Resolve an absolute (0-indexed) page to (spineIndex, pageInSpine).
     *  Out-of-range values clamp to the last page of the last spine item. */
    fun spineAndPageFor(pageCounts: IntArray, absolute: Int): Pair<Int, Int> {
        if (pageCounts.isEmpty()) return 0 to 0
        var remaining = absolute.coerceAtLeast(0)
        for (i in pageCounts.indices) {
            val c = pageCounts[i].coerceAtLeast(1)
            if (remaining < c) return i to remaining
            remaining -= c
        }
        val last = pageCounts.lastIndex
        return last to (pageCounts[last].coerceAtLeast(1) - 1)
    }
}
