package com.epubreader.app.epub

import java.io.File
import java.util.zip.ZipFile

/**
 * Full-text search across an EPUB's spine. Strips markup from each chapter and
 * returns matches with a snippet of surrounding text.
 */
class EpubSearchEngine(private val epubFile: File) {

    data class Result(
        val chapterIndex: Int,
        val chapterTitle: String,
        val snippet: String
    )

    private val tagRegex = Regex("<[^>]*>")
    private val blockRegex = Regex("<(script|style)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)

    fun search(spine: List<SpineItem>, tocTitles: Map<String, String>, query: String): List<Result> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val results = mutableListOf<Result>()
        ZipFile(epubFile).use { zip ->
            spine.forEachIndexed { index, item ->
                val entry = zip.getEntry(item.href) ?: return@forEachIndexed
                val raw = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                val text = stripTags(raw)
                var start = 0
                while (true) {
                    val pos = text.indexOf(q, start, ignoreCase = true)
                    if (pos < 0) break
                    val s = (pos - 50).coerceAtLeast(0)
                    val e = (pos + q.length + 50).coerceAtMost(text.length)
                    val snippet = text.substring(s, e).replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
                    results.add(Result(index, tocTitles[item.href] ?: chapterFallback(item, index), snippet))
                    start = pos + q.length
                }
            }
        }
        return results
    }

    private fun chapterFallback(item: SpineItem, index: Int): String =
        item.href.substringAfterLast('/').substringBeforeLast('.')

    private fun stripTags(html: String): String {
        val withoutBlocks = blockRegex.replace(html, " ")
        return tagRegex.replace(withoutBlocks, " ")
    }
}
