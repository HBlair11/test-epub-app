package com.epubreader.app.epub

/**
 * Pure (Android-free) decision for the rescan fast-path: whether an already
 * imported book can be skipped because its source file is unchanged.
 *
 * A book is skipped only when ALL hold:
 *  - the cached epub file still exists on disk,
 *  - the source size is known and positive,
 *  - the cached size + mtime exactly match the source size + mtime.
 *
 * Extracted from EpubImporter so it can be unit tested on the JVM.
 */
object RescanDecision {
    fun shouldSkip(
        existingFileSize: Long,
        existingMtime: Long,
        sourceSize: Long,
        sourceMtime: Long,
        cachedFileExists: Boolean
    ): Boolean {
        if (!cachedFileExists) return false
        if (sourceSize <= 0L) return false
        return existingFileSize == sourceSize && existingMtime == sourceMtime
    }
}
