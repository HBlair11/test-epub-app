package com.epubreader.app.epub

import android.net.Uri

/**
 * Single source of truth for which book file types the app accepts.
 *
 * Patch 18 (Addition #3): the app renders EPUB only, so the accepted set is
 * EPUB alone. PDF (and any future format) is a one-place change here — add it
 * to [ALL] and [acceptedMimeTypes] and every consumer (the import picker MIME
 * filter, the folder scanner extension check, the "Open with" intent filter)
 * picks it up automatically. NOTE: adding a type here does NOT make the reader
 * able to render it — that requires a separate viewer (see PATCH18_NOTES §3).
 */
object BookFileTypes {

    /** Canonical accepted file extensions (lowercase, without the dot). */
    val ALL: List<String> = listOf("epub")

    /** MIME types passed to the SAF file picker to filter visible files. */
    val acceptedMimeTypes: Array<String> = arrayOf("application/epub+zip", "application/epub")

    /** True if [name] ends with an accepted book extension (case-insensitive). */
    fun isBookFile(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase()
        return ALL.any { ext -> lower.endsWith(".$ext") }
    }

    /** True if [uri]'s display name ends with an accepted book extension.
     *  Defensive against providers that throw on query() (some file:// and
     *  non-Documents content:// URIs do) — falls back to the URI's last path
     *  segment. Never throws. */
    fun isBookFile(uri: Uri, contentResolver: android.content.ContentResolver): Boolean {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = c.getString(idx)
                    }
                }
        } catch (_: Exception) {
            // Provider threw (common for file:// or unsupported content://).
            // Fall through to lastPathSegment below.
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment ?: uri.path
        return isBookFile(name)
    }
}
