package com.epubreader.app.epub

/**
 * TTS-specific representation of one XHTML spine item.
 *
 * This model deliberately stays scoped to Read Aloud. It preserves the
 * structural boundaries that Android TextToSpeech needs without changing the
 * app's general EPUB/document architecture.
 */
data class ReaderTtsDocument(
    val href: String,
    val rawText: String,
    val blocks: List<ReaderTtsBlock>,
)

data class ReaderTtsBlock(
    val text: String,
    val kind: ReaderTtsBlockKind,
    /** Character range in [ReaderTtsDocument.rawText]. */
    val rawStart: Int,
    val rawEnd: Int,
    val headingLevel: Int = 0,
)

enum class ReaderTtsBlockKind {
    PARAGRAPH,
    HEADING,
    LIST_ITEM,
    QUOTE,
    OTHER,
}

/**
 * A spoken unit. Offsets are relative to the owning TTS document's raw text.
 * Keeping these offsets here gives later highlighting/current-page work a
 * stable source location instead of searching for strings in the WebView.
 */
data class ReaderTtsSegment(
    val text: String,
    val pauseAfterMs: Long,
    val rawStart: Int,
    val rawEnd: Int,
    val blockIndex: Int,
    /** Normalized text offsets within the owning structural block. */
    val blockTextStart: Int = 0,
    val blockTextEnd: Int = text.length,
)
