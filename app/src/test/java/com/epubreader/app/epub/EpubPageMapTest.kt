package com.epubreader.app.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubPageMapTest {

    @Test
    fun `toPageCount is one for empty or tiny text`() {
        assertEquals(1, EpubPageMap.toPageCount(0))
        assertEquals(1, EpubPageMap.toPageCount(1))
        assertEquals(1, EpubPageMap.toPageCount(1024 - 1))
    }

    @Test
    fun `toPageCount crosses a page boundary at 1024 bytes`() {
        assertEquals(1, EpubPageMap.toPageCount(1024))
        assertEquals(2, EpubPageMap.toPageCount(1025))
        assertEquals(2, EpubPageMap.toPageCount(2048))
        assertEquals(3, EpubPageMap.toPageCount(2049))
    }

    @Test
    fun `toPageCounts maps per-spine byte counts to pages`() {
        val bytes = intArrayOf(0, 500, 1025, 3000)
        // 0->1, 500->1, 1025->2, 3000->3 (ceil(3000/1024)=3)
        assertEquals(listOf(1, 1, 2, 3), EpubPageMap.toPageCounts(bytes).toList())
    }

    @Test
    fun `visibleText strips tags script and style`() {
        val html = """
            <html><head><style>body{color:red}</style>
            <script>var x = 1;</script></head>
            <body><h1>Title</h1><p>Hello &amp; goodbye&nbsp;world</p></body></html>
        """.trimIndent()
        val text = EpubPageMap.visibleText(html)
        // Tags, style, and script content removed; entities decoded.
        assert(!text.contains("<"))
        assert(!text.contains("color:red"))
        assert(!text.contains("var x"))
        assert(text.contains("Title"))
        assert(text.contains("Hello & goodbye world"))
    }

    @Test
    fun `visibleTextBytes counts UTF-8 bytes of visible text only`() {
        val html = "<p>abc</p><style>hidden</style>"
        // Visible text is "abc" = 3 bytes.
        assertEquals(3, EpubPageMap.visibleTextBytes(html))
    }

    @Test
    fun `multi-byte UTF-8 characters count by bytes not chars`() {
        // "é" is 2 UTF-8 bytes; "€" is 3.
        val html = "<p>é€</p>"
        assertEquals(5, EpubPageMap.visibleTextBytes(html))
    }
}
