package com.epubreader.app.epub

import android.text.Html
import android.text.Spanned
import java.util.Locale

/**
 * Builds a TTS-only document model from EPUB XHTML without executing the
 * content. The parser is intentionally small and tolerant because EPUB XHTML
 * in the wild is not always perfectly XML-shaped.
 *
 * The important distinction from the previous implementation is that source
 * text-node order and block boundaries are retained instead of flattening the
 * entire chapter before segmentation.
 */
object ReaderTtsDocumentBuilder {

    private val tagRegex = Regex("(?is)<!--.*?-->|<!\\[CDATA\\[.*?]]>|<[^>]*>|[^<]+")
    private val tagNameRegex = Regex("(?is)^<\\s*(/?)\\s*([a-z0-9]+)\\b[^>]*?(\\/?)\\s*>$")
    private val whitespaceRegex = Regex("\\s+")

    private val blockTags = setOf(
        "address", "article", "aside", "blockquote", "dd", "div", "dl", "dt",
        "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4",
        "h5", "h6", "header", "li", "main", "nav", "ol", "p", "pre", "section",
        "table", "td", "th", "tr", "ul"
    )

    private val ignoredTags = setOf("head", "script", "style", "noscript", "svg", "math")

    private data class MutableBlock(
        val kind: ReaderTtsBlockKind,
        val headingLevel: Int,
        var rawStart: Int = -1,
        var rawEnd: Int = -1,
        val pieces: MutableList<String> = mutableListOf(),
        var directTextLength: Int = 0,
        var hasChildBlock: Boolean = false,
    )

    private data class OpenBlock(
        val tag: String,
        val block: MutableBlock,
    )

    fun build(href: String, html: String): ReaderTtsDocument {
        val rawText = StringBuilder()
        val blocks = mutableListOf<ReaderTtsBlock>()
        val stack = mutableListOf<OpenBlock>()
        var ignoredDepth = 0

        fun currentBlock(): MutableBlock? = stack.lastOrNull()?.block

        fun emit(block: MutableBlock) {
            if (block.hasChildBlock) return
            val text = normalize(block.pieces.joinToString(""))
            if (text.isBlank()) return

            val start = if (block.rawStart >= 0) block.rawStart else 0
            val end = if (block.rawEnd >= start) block.rawEnd else start
            val kind = block.kind
            blocks += ReaderTtsBlock(
                text = text,
                kind = kind,
                rawStart = start,
                rawEnd = maxOf(end, start + text.length),
                headingLevel = block.headingLevel,
            )
        }

        fun closeTag(tag: String) {
            val index = stack.indexOfLast { it.tag == tag }
            if (index < 0) return

            while (stack.lastIndex >= index) {
                val open = stack.removeAt(stack.lastIndex)
                open.block.rawEnd = maxOf(open.block.rawEnd, rawText.length)
                emit(open.block)
            }
        }

        for (match in tagRegex.findAll(html)) {
            val token = match.value
            if (token.startsWith("<!--") || token.startsWith("<![CDATA[")) continue

            if (token.startsWith("<")) {
                val parsed = tagNameRegex.matchEntire(token) ?: continue
                val closing = parsed.groupValues[1] == "/"
                val tag = parsed.groupValues[2].lowercase(Locale.US)
                val selfClosing = parsed.groupValues[3] == "/" ||
                    tag in setOf("br", "img", "hr", "meta", "link", "input", "source", "wbr")

                if (tag in ignoredTags) {
                    if (closing) ignoredDepth = maxOf(0, ignoredDepth - 1)
                    else if (!selfClosing) ignoredDepth++
                    continue
                }
                if (ignoredDepth > 0) continue

                if (!closing && tag == "br") {
                    val space = " "
                    val start = rawText.length
                    rawText.append(space)
                    currentBlock()?.let { block ->
                        if (block.rawStart < 0) block.rawStart = start
                        block.rawEnd = rawText.length
                        block.pieces += space
                        block.directTextLength += space.length
                    }
                    continue
                }

                if (closing) {
                    if (tag in blockTags) closeTag(tag)
                    continue
                }

                if (tag in blockTags) {
                    currentBlock()?.let { it.hasChildBlock = true }
                    val kind = when {
                        tag.matches(Regex("h[1-6]")) -> ReaderTtsBlockKind.HEADING
                        tag == "li" -> ReaderTtsBlockKind.LIST_ITEM
                        tag == "blockquote" -> ReaderTtsBlockKind.QUOTE
                        tag in setOf("p", "pre") -> ReaderTtsBlockKind.PARAGRAPH
                        else -> ReaderTtsBlockKind.OTHER
                    }
                    val level = tag.removePrefix("h").toIntOrNull() ?: 0
                    stack += OpenBlock(tag, MutableBlock(kind, level, rawStart = rawText.length))
                    if (selfClosing) closeTag(tag)
                }
                continue
            }

            if (ignoredDepth > 0) continue
            val decoded = decodeText(token)
            if (decoded.isEmpty()) continue

            val start = rawText.length
            rawText.append(decoded)
            currentBlock()?.let { block ->
                if (block.rawStart < 0) block.rawStart = start
                block.rawEnd = rawText.length
                block.pieces += decoded
                block.directTextLength += decoded.length
            }
        }

        while (stack.isNotEmpty()) {
            val open = stack.removeAt(stack.lastIndex)
            open.block.rawEnd = maxOf(open.block.rawEnd, rawText.length)
            emit(open.block)
        }

        // Text outside an explicit block is uncommon in EPUB XHTML, but keep it
        // readable rather than silently dropping it.
        if (blocks.isEmpty()) {
            val fallback = normalize(rawText.toString())
            if (fallback.isNotBlank()) {
                blocks += ReaderTtsBlock(
                    text = fallback,
                    kind = ReaderTtsBlockKind.OTHER,
                    rawStart = 0,
                    rawEnd = rawText.length,
                )
            }
        }

        return ReaderTtsDocument(href, rawText.toString(), blocks.sortedBy { it.rawStart })
    }

    private fun normalize(value: String): String =
        whitespaceRegex.replace(value, " ").trim()

    private fun decodeText(value: String): String {
        if (value.isEmpty()) return ""
        val spanned: Spanned = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
        return spanned.toString()
    }
}
