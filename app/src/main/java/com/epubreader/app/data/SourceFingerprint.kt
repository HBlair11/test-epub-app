package com.epubreader.app.data

/**
 * Lightweight fingerprint of an already-imported book, used by the rescan
 * fast-path: instead of querying the DB once per file in a folder of hundreds
 * of books (hundreds of round-trips), the scanner loads every fingerprint in a
 * single query and checks each scanned file against this in-memory map.
 *
 * A book is skipped only when [fileSize] + [sourceLastModified] match the
 * scanned file's size + mtime AND the cached epub [path] still exists — see
 * [com.epubreader.app.epub.RescanDecision].
 */
data class SourceFingerprint(
    val id: Long,
    @androidx.room.ColumnInfo(name = "source_uri") val sourceUri: String?,
    @androidx.room.ColumnInfo(name = "source_filename") val sourceFilename: String,
    @androidx.room.ColumnInfo(name = "file_size") val fileSize: Long,
    @androidx.room.ColumnInfo(name = "source_last_modified") val sourceLastModified: Long,
    val path: String,
)
