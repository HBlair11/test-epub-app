package com.epubreader.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY spine_index, scroll_ratio")
    fun observeForBook(bookId: Long): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE book_id = :bookId AND spine_index = :spineIndex AND ABS(scroll_ratio - :ratio) < 0.01")
    suspend fun deleteNear(bookId: Long, spineIndex: Int, ratio: Float)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE book_id = :bookId AND spine_index = :spineIndex AND ABS(scroll_ratio - :ratio) < 0.01)")
    suspend fun existsNear(bookId: Long, spineIndex: Int, ratio: Float): Boolean
}
