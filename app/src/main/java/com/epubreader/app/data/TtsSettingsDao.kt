package com.epubreader.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TtsSettingsDao {
    @Query("SELECT * FROM tts_settings WHERE book_id = :bookId LIMIT 1")
    suspend fun getForBook(bookId: Long): TtsSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: TtsSettingsEntity)
}
