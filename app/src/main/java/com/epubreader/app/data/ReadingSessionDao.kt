package com.epubreader.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReadingSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ReadingSessionEntity): Long

    @Query("SELECT COALESCE(SUM(active_seconds), 0) FROM reading_sessions WHERE started_at >= :since")
    suspend fun activeSecondsSince(since: Long): Int

    @Query("SELECT COALESCE(SUM(active_seconds), 0) FROM reading_sessions WHERE started_at >= :since")
    suspend fun activeSecondsSinceYear(since: Long): Int

    @Query("SELECT COALESCE(SUM(chapters_advanced), 0) FROM reading_sessions WHERE started_at >= :since")
    suspend fun chaptersSince(since: Long): Int

    @Query("SELECT COALESCE(SUM(pages_advanced), 0) FROM reading_sessions WHERE started_at >= :since")
    suspend fun pagesSince(since: Long): Int

    @Query("SELECT DISTINCT date(started_at / 1000, 'unixepoch', 'localtime') FROM reading_sessions WHERE active_seconds > 0 ORDER BY started_at DESC")
    suspend fun activeDays(): List<String>
}
