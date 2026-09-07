package com.epubreader.app.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderProgressMathTest {

    @Test
    fun `chapter level progress is stable before page measurement`() {
        assertEquals(0.4f, ReaderProgressMath.overallProgress(4, 0f, 10, null), 0.0001f)
        assertEquals(0.45f, ReaderProgressMath.overallProgress(4, 0.5f, 10, null), 0.0001f)
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

}
