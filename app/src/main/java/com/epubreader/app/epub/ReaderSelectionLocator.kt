package com.epubreader.app.epub

/**
 * Renderer-independent locator captured from a native WebView text selection.
 * DOM paths are retained for precise same-renderer restoration; normalized
 * offsets plus prefix/suffix make the location portable across reflow.
 */
data class ReaderSelectionLocator(
    val text: String,
    val spineHref: String,
    val startPath: String,
    val startOffset: Int,
    val endPath: String,
    val endOffset: Int,
    val normalizedStart: Int = 0,
    val normalizedEnd: Int = 0,
    val prefix: String = "",
    val suffix: String = "",
)
