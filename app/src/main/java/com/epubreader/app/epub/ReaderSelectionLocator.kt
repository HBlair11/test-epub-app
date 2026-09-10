package com.epubreader.app.epub

/** Stable, renderer-independent selection payload for future highlights/notes/dictionary features. */
data class ReaderSelectionLocator(
    val text: String,
    val spineHref: String,
    val startPath: String,
    val startOffset: Int,
    val endPath: String,
    val endOffset: Int,
    val prefix: String = "",
    val suffix: String = "",
)
