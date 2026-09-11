package com.epubreader.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Patch v37: offline dictionary lookup history. Every Define lookup is saved
 * here (most recent first) so the reader can review difficult vocabulary
 * later and export it to CSV/Markdown for flashcard apps like Anki.
 * A UNIQUE index on word makes re-lookups refresh the timestamp instead of
 * duplicating rows.
 */
@Entity(
    tableName = "dictionary_history",
    indices = [Index(value = ["word"], unique = true)],
)
data class DictionaryHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "word") val word: String,
    @ColumnInfo(name = "definition") val definition: String? = null,
    @ColumnInfo(name = "part_of_speech") val partOfSpeech: String? = null,
    /** Book the word was looked up from; informational only (no FK so the
     *  vocabulary survives a book removal). */
    @ColumnInfo(name = "book_id") val bookId: Long? = null,
    @ColumnInfo(name = "looked_up_at") val lookedUpAt: Long = System.currentTimeMillis(),
)
