package com.epubreader.app.epub

import android.webkit.JavascriptInterface

/** Narrow bridge: selection data only; no filesystem, database, or mutation access. */
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
        normalizedStart: Int,
        normalizedEnd: Int,
        prefix: String,
        suffix: String,
    ) {
        val cleanText = text.trim()
        if (cleanText.isBlank() || spineHref.isBlank()) return
        onSelection(
            ReaderSelectionLocator(
                text = cleanText,
                spineHref = spineHref,
                startPath = startPath,
                startOffset = startOffset,
                endPath = endPath,
                endOffset = endOffset,
                normalizedStart = normalizedStart.coerceAtLeast(0),
                normalizedEnd = normalizedEnd.coerceAtLeast(normalizedStart),
                prefix = prefix.takeLast(80),
                suffix = suffix.take(80),
            ),
        )
    }
}
