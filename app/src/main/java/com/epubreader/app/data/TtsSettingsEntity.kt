package com.epubreader.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Patch v37: per-book TTS audio settings. Restores the user's preferred
 * speech rate, pitch, and voice for a specific book automatically on open,
 * so switching between fiction and technical manuals doesn't reset
 * preferences. One row per book; a missing row means "use global defaults".
 */
@Entity(
    tableName = "tts_settings",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class TtsSettingsEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "speech_rate") val speechRate: Float,
    @ColumnInfo(name = "pitch") val pitch: Float,
    @ColumnInfo(name = "voice_name") val voiceName: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)
