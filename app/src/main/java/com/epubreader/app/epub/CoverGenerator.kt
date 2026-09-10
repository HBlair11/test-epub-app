package com.epubreader.app.epub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream

/** Generates a calm, readable fallback cover when an EPUB has no usable cover. */
object CoverGenerator {
    fun generate(title: String, author: String, targetFile: File): File? {
        return try {
            val width = 900
            val height = 1350
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFF574F7D.toInt())

            val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE0F0EA.toInt()
                strokeWidth = 8f
                style = Paint.Style.STROKE
            }
            canvas.drawRoundRect(RectF(64f, 64f, width - 64f, height - 64f), 24f, 24f, accent)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 64f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
            }
            val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE0F0EA.toInt()
                textSize = 34f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
            }

            drawWrapped(canvas, title.ifBlank { "Untitled" }, titlePaint, 120f, 420f, width - 240f, 96f)
            drawWrapped(canvas, author.ifBlank { "Unknown author" }, authorPaint, 120f, 1080f, width - 240f, 54f)

            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }
            bitmap.recycle()
            targetFile
        } catch (_: Exception) {
            null
        }
    }

    private fun drawWrapped(canvas: Canvas, text: String, paint: Paint, left: Float, baseline: Float, maxWidth: Float, lineHeight: Float) {
        val words = text.replace("\n", " ").split(Regex("\\s+"))
        var line = ""
        var y = baseline
        for (word in words) {
            val candidate = if (line.isBlank()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotBlank()) {
                canvas.drawText(line, left, y, paint)
                y += lineHeight
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotBlank()) canvas.drawText(line, left, y, paint)
    }
}
