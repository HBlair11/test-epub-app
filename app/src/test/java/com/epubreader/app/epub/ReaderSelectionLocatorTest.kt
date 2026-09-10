package com.epubreader.app.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSelectionLocatorTest {
    @Test
    fun locatorRetainsStableSelectionContext() {
        val locator = ReaderSelectionLocator(
            text = "Rivals",
            spineHref = "Text/ch07.xhtml",
            startPath = "body:0/p:3",
            startOffset = 12,
            endPath = "body:0/p:3",
            endOffset = 18,
            prefix = "the sky-high ",
            suffix = " appeared",
        )

        assertEquals("Rivals", locator.text)
        assertEquals("Text/ch07.xhtml", locator.spineHref)
        assertEquals(12, locator.startOffset)
        assertEquals(18, locator.endOffset)
        assertTrue(locator.prefix.contains("sky-high"))
        assertTrue(locator.suffix.contains("appeared"))
    }
}
