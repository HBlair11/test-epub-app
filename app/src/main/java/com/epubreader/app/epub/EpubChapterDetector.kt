package com.epubreader.app.epub

import android.text.Html
import java.util.zip.ZipFile

/**
 * Detects content chapters for user-facing chapter counts.
 *
 * EPUB spine items are rendering/navigation units and commonly include title pages,
 * contents, copyright, dedication, acknowledgements, epilogues, appendices, notes,
 * bibliography and other front/back matter. This detector intentionally uses the
 * embedded TOC plus the first real heading rather than treating every spine item as
 * a chapter.
 */
object EpubChapterDetector {

    private val excluded = listOf(
        "cover", "title page", "titlepage", "half title", "copyright",
        "contents", "table of contents", "toc", "dedication", "epigraph",
        "acknowledgements", "acknowledgments", "foreword", "preface",
        "introduction", "prologue", "epilogue", "afterword", "appendix",
        "appendices", "notes", "endnotes", "bibliography", "references",
        "glossary", "index", "about the author", "author's note",
    )

    fun detect(book: EpubBook): List<Int> {
        val tocBySpine = linkedMapOf<Int, TocEntry>()
        for (entry in book.toc) {
            val path = entry.href.substringBefore('#').substringBefore('?').trimStart('/')
            val index = book.spine.indexOfFirst {
                it.href.substringBefore('#').substringBefore('?').trimStart('/') == path
            }
            if (index >= 0 && index !in tocBySpine) {
                tocBySpine[index] = entry
            }
        }

        val candidates = mutableListOf<Int>()
        ZipFile(book.file).use { zip ->
            for (index in book.spine.indices) {
                val spine = book.spine[index]
                val htmlLike = spine.mediaType.contains("html", ignoreCase = true) ||
                    spine.mediaType.contains("xhtml", ignoreCase = true)
                if (!spine.linear || !htmlLike) continue

                val toc = tocBySpine[index]
                val heading = readFirstHeading(zip, spine.href)
                val label = toc?.label.orEmpty().ifBlank { heading }
                if (isExcluded(label)) continue
                if (label.isBlank() && heading.isBlank()) continue

                val chapterLike = looksLikeChapter(label) || looksLikeChapter(heading)
                val topLevelToc = toc != null && toc.level <= 1
                val hasHeading = heading.isNotBlank()

                if (chapterLike || (topLevelToc && hasHeading)) {
                    candidates += index
                }
            }
        }

        return candidates.distinct().sorted()
    }

    fun ordinalForSpine(chapterSpines: List<Int>, spineIndex: Int): Int {
        val exact = chapterSpines.indexOf(spineIndex)
        if (exact >= 0) return exact + 1
        val previous = chapterSpines.indexOfLast { it <= spineIndex }
        return if (previous >= 0) previous + 1 else 0
    }

    private fun looksLikeChapter(value: String): Boolean {
        val text = value.trim()
        if (text.isBlank()) return false
        return text.matches(
            Regex(
                "^(?i:chapter)\\s+.+$|^(?i:chapter)\\b.*$|^(?:[0-9]+|[IVXLCDM]+)(?:[.:\\-\\s].*)?$",
                RegexOption.IGNORE_CASE,
            )
        )
    }

    private fun isExcluded(value: String): Boolean {
        val normalized = value
            .lowercase()
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return true
        if (excluded.any { normalized == it || normalized.startsWith("$it ") }) return true
        return normalized.startsWith("part ") || normalized.startsWith("volume ")
    }

    private fun readFirstHeading(zip: ZipFile, href: String): String {
        val entry = zip.getEntry(href) ?: return ""
        val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val match = Regex(
            "<h[1-3]\\b[^>]*>(.*?)</h[1-3]>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(xml) ?: return ""
        return Html.fromHtml(match.groupValues[1], Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
