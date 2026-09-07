package com.epubreader.app.epub

import java.util.zip.ZipFile

/**
 * Synthetic "book page" estimation using the Adobe Digital Editions (ADE)
 * byte-mapping model: a page is roughly 1024 bytes of visible text.
 *
 * Why this model: a real, screen-accurate page count for a reflowable EPUB can
 * only be obtained by laying out the text at the current font size / line height
 * / viewport — which takes hundreds of milliseconds for a 100k-word book and
 * changes every time the reader settings change. The ADE model instead divides
 * the book's visible text byte length into fixed 1024-byte "book pages."
 *
 * Consequences (these are features, not bugs):
 *  - The total page count is **instant** (just reading text lengths, no layout).
 *  - The total is **stable**: changing the font size does NOT change the total;
 *    you may swipe more than once to advance one "book page."
 *  - It is an estimate (like Kindle's "Location" / ReadEra's page numbers), not a
 *    guaranteed one-swipe-per-page screen count.
 *
 * `compute` reads the EPUB zip; the per-element helpers (`toPageCount`,
 * `toPageCounts`, `visibleTextBytes`) are pure and unit-tested on the JVM.
 */
object EpubPageMap {

    /** ADE standard: ~1024 bytes of uncompressed text per "page." */
    const val BYTES_PER_PAGE = 1024

    private val SCRIPT_STYLE = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val WHITESPACE = Regex("\\s+")

    /** Strip tags / script / style and return the visible text as a String. */
    fun visibleText(html: String): String {
        var s = SCRIPT_STYLE.replace(html, " ")
        s = TAG.replace(s, " ")
        s = decodeEntities(s)
        return WHITESPACE.replace(s, " ").trim()
    }

    /** UTF-8 byte length of the visible text (what the ADE byte-map counts). */
    fun visibleTextBytes(html: String): Int =
        visibleText(html).toByteArray(Charsets.UTF_8).size

    /** Number of book pages for a given visible-text byte count (min 1). */
    fun toPageCount(bytes: Int): Int =
        if (bytes <= 0) 1 else (bytes + BYTES_PER_PAGE - 1) / BYTES_PER_PAGE

    /** Per-spine page counts from per-spine byte counts. */
    fun toPageCounts(byteCounts: IntArray): IntArray =
        IntArray(byteCounts.size) { toPageCount(byteCounts[it]) }

    /**
     * Read every linear spine resource from the EPUB zip, count its visible
     * text bytes, and return per-spine book-page counts. Non-linear / missing
     * entries default to 1 page. This is a fast text-only pass (no layout).
     */
    fun compute(book: EpubBook): IntArray {
        val counts = IntArray(book.spine.size) { 1 }
        if (book.spine.isEmpty()) return counts
        try {
            ZipFile(book.file).use { zip ->
                book.spine.forEachIndexed { i, item ->
                    if (!item.linear) return@forEachIndexed
                    val entry = zip.getEntry(item.href) ?: return@forEachIndexed
                    val html = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    counts[i] = toPageCount(visibleTextBytes(html))
                }
            }
        } catch (_: Exception) {
            // Fall back to "1 page per spine" — the seeker/indicator still work.
        }
        return counts
    }

    /** Decode the common HTML entities that affect byte count meaningfully. */
    private fun decodeEntities(s: String): String {
        return s
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#160;", " ")
    }
}
