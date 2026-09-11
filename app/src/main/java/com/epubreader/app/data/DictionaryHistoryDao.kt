package com.epubreader.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryHistoryDao {
    @Query("SELECT * FROM dictionary_history ORDER BY looked_up_at DESC")
    fun observeAll(): Flow<List<DictionaryHistoryEntity>>

    @Query("SELECT * FROM dictionary_history ORDER BY looked_up_at ASC")
    suspend fun getAll(): List<DictionaryHistoryEntity>

    @Query("SELECT * FROM dictionary_history WHERE word = :word LIMIT 1")
    suspend fun find(word: String): DictionaryHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DictionaryHistoryEntity)

    @Query("UPDATE dictionary_history SET definition = :definition, part_of_speech = :partOfSpeech, book_id = :bookId, looked_up_at = :lookedUpAt WHERE id = :id")
    suspend fun refresh(id: Long, definition: String?, partOfSpeech: String?, bookId: Long?, lookedUpAt: Long)

    @Delete
    suspend fun delete(entry: DictionaryHistoryEntity)

    @Query("DELETE FROM dictionary_history")
    suspend fun clear()
}
