package com.epubreader.app.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/** Extracts a cover image from an EPUB and writes it to a file. */
object CoverExtractor {

    fun extract(epubFile: File, book: EpubBook, targetFile: File): File? {
        val coverHref = book.coverHref ?: return null
        return try {
            ZipFile(epubFile).use { zip ->
                val entry = zip.getEntry(coverHref)
                    ?: zip.entries().asSequence().firstOrNull { it.name == coverHref }
                    ?: return null
                zip.getInputStream(entry).use { input ->
                    val bytes = input.readBytes()
                    val bitmap = decode(bytes) ?: return null
                    FileOutputStream(targetFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                    targetFile
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
