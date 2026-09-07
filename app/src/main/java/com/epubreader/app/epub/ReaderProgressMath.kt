package com.epubreader.app.epub

/** Pure reader-position calculations kept outside the Activity so they can be regression-tested. */
object ReaderProgressMath {
    fun overallProgress(
        spineIndex: Int,
        scrollRatio: Float,
        spineCount: Int,
        pageCounts: IntArray?,
    ): Float {
        if (spineCount <= 0) return 0f
        val ratio = scrollRatio.coerceIn(0f, 1f)
        val index = spineIndex.coerceIn(0, spineCount - 1)
        if (pageCounts != null && pageCounts.size == spineCount && pageCounts.none { it < 0 }) {
            val total = ReaderPageMapping.totalPages(pageCounts)
            if (total <= 0) return 0f
            val prefix = ReaderPageMapping.prefixSums(pageCounts)
            val inSpine = pageCounts[index].coerceAtLeast(1)
            val absolute = (prefix[index] + ratio * inSpine).coerceAtMost(total.toFloat())
            return (absolute / total.toFloat()).coerceIn(0f, 1f)
        }
        return ((index + ratio) / spineCount.toFloat()).coerceIn(0f, 1f)
    }

    fun syntheticPageInSpine(scrollRatio: Float, pageCount: Int): Int {
        val pages = pageCount.coerceAtLeast(1)
        if (pages <= 1) return 0
        return (scrollRatio.coerceIn(0f, 1f) * (pages - 1))
            .roundToInt()
            .coerceIn(0, pages - 1)
    }

    private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()
}
