package com.epubreader.app.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderProgressMathTest {

    @Test
    fun `chapter level progress is stable before page measurement`() {
        assertEquals(0.5f, ReaderProgressMath.overallProgress(4, 0f, 10, null), 0.0001f)
        assertEquals(0.55f, ReaderProgressMath.overallProgress(4, 0.5f, 10, null), 0.0001f)
    }

    @Test
    fun `page mapped progress uses measured page counts`() {
        val counts = intArrayOf(3, 5, 2, 4)
        assertEquals(
            5f / 14f,
            ReaderProgressMath.overallProgress(1, 0.4f, 4, counts),
            0.0001f,
        )
    }

    @Test
    fun `synthetic page clamps ratio and page count`() {
        assertEquals(0, ReaderProgressMath.syntheticPageInSpine(-1f, 1))
        assertEquals(0, ReaderProgressMath.syntheticPageInSpine(0f, 5))
        assertEquals(2, ReaderProgressMath.syntheticPageInSpine(0.5f, 5))
        assertEquals(4, ReaderProgressMath.syntheticPageInSpine(2f, 5))
    }
}
