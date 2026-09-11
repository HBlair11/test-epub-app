package com.epubreader.app.epub

/** Stable, renderer-independent selection payload for highlights/notes/dictionary features.
 *  The rect fields carry the selection's bounding box in WebView-local CSS
 *  pixels so contextual UI (definition card) can be anchored near the
 *  selection without extra round trips. */
data class ReaderSelectionLocator(
    val text: String,
    val spineHref: String,
    val startPath: String,
    val startOffset: Int,
    val endPath: String,
    val endOffset: Int,
    val prefix: String = "",
    val suffix: String = "",
    val rectLeft: Int = 0,
    val rectTop: Int = 0,
    val rectRight: Int = 0,
    val rectBottom: Int = 0,
) {
    val hasRect: Boolean get() = rectRight > rectLeft && rectBottom > rectTop
}
