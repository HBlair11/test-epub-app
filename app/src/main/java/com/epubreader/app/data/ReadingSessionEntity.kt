package com.epubreader.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_sessions",
    indices = [Index(value = ["started_at"]), Index(value = ["book_id", "started_at"])],
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long,
    @ColumnInfo(name = "active_seconds") val activeSeconds: Int,
    @ColumnInfo(name = "chapters_advanced") val chaptersAdvanced: Int,
    @ColumnInfo(name = "pages_advanced") val pagesAdvanced: Int,
)
