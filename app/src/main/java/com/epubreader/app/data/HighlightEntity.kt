package com.epubreader.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A durable reader annotation. The locator stores both a DOM-aware selection
 * path and renderer-independent text/context so it can be relocated after
 * pagination, typography, or orientation changes.
 */
@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("book_id"), Index(value = ["book_id", "spine_href"])],
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "spine_href") val spineHref: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "color") val color: Int,
    @ColumnInfo(name = "prefix") val prefix: String = "",
    @ColumnInfo(name = "suffix") val suffix: String = "",
    @ColumnInfo(name = "start_path") val startPath: String = "",
    @ColumnInfo(name = "end_path") val endPath: String = "",
    @ColumnInfo(name = "start_offset") val startOffset: Int = 0,
    @ColumnInfo(name = "end_offset") val endOffset: Int = 0,
    @ColumnInfo(name = "normalized_start") val normalizedStart: Int = 0,
    @ColumnInfo(name = "normalized_end") val normalizedEnd: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)
