package com.epubreader.app.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RescanDecisionTest {

    private val size = 1_000_000L
    private val mtime = 1_700_000_000_000L

    @Test
    fun `unchanged file is skipped`() {
        assertTrue(
            RescanDecision.shouldSkip(
                existingFileSize = size,
                existingMtime = mtime,
                sourceSize = size,
                sourceMtime = mtime,
                cachedFileExists = true
            )
        )
    }

    @Test
    fun `changed size is not skipped`() {
        assertFalse(
            RescanDecision.shouldSkip(
                existingFileSize = size,
                existingMtime = mtime,
                sourceSize = size + 10,
                sourceMtime = mtime,
                cachedFileExists = true
            )
        )
    }

    @Test
    fun `changed mtime is not skipped`() {
        assertFalse(
            RescanDecision.shouldSkip(
                existingFileSize = size,
                existingMtime = mtime,
                sourceSize = size,
                sourceMtime = mtime + 1,
                cachedFileExists = true
            )
        )
    }

    @Test
    fun `missing cached file is not skipped`() {
        // Even if size + mtime match, a missing cached epub must be re-imported.
        assertFalse(
            RescanDecision.shouldSkip(
                existingFileSize = size,
                existingMtime = mtime,
                sourceSize = size,
                sourceMtime = mtime,
                cachedFileExists = false
            )
        )
    }

    @Test
    fun `unknown source size is not skipped`() {
        // A size of 0 means metadata could not be read — fall back to full import.
        assertFalse(
            RescanDecision.shouldSkip(
                existingFileSize = size,
                existingMtime = mtime,
                sourceSize = 0L,
                sourceMtime = mtime,
                cachedFileExists = true
            )
        )
    }
}
