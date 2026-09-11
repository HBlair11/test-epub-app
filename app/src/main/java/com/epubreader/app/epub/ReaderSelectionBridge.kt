package com.epubreader.app.epub

import android.webkit.JavascriptInterface

/** Narrow bridge used only for extracting the user's current text selection. */
class ReaderSelectionBridge(
    private val onSelection: (ReaderSelectionLocator) -> Unit,
) {
    @JavascriptInterface
    fun onSelectionPayload(
        text: String,
        spineHref: String,
        startPath: String,
        startOffset: Int,
        endPath: String,
        endOffset: Int,
        prefix: String,
        suffix: String,
        rectLeft: Int,
        rectTop: Int,
        rectRight: Int,
        rectBottom: Int,
    ) {
        if (text.isBlank() || spineHref.isBlank()) return
        onSelection(
            ReaderSelectionLocator(
                text = text.trim(),
                spineHref = spineHref,
                startPath = startPath,
                startOffset = startOffset,
                endPath = endPath,
                endOffset = endOffset,
                prefix = prefix,
                suffix = suffix,
                rectLeft = rectLeft,
                rectTop = rectTop,
                rectRight = rectRight,
                rectBottom = rectBottom,
            )
        )
    }
}
