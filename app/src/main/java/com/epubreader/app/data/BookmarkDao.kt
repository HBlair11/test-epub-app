package com.epubreader.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY created_at DESC, id DESC")
    fun observeForBook(bookId: Long): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE book_id = :bookId AND spine_index = :spineIndex AND ABS(scroll_ratio - :ratio) < 0.01")
    suspend fun deleteNear(bookId: Long, spineIndex: Int, ratio: Float)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE book_id = :bookId AND spine_index = :spineIndex AND ABS(scroll_ratio - :ratio) < 0.01)")
    suspend fun existsNear(bookId: Long, spineIndex: Int, ratio: Float): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE book_id = :bookId AND spine_index = :spineIndex AND page_in_chapter = :page AND snippet = :snippet)")
    suspend fun existsNearWithSnippet(bookId: Long, spineIndex: Int, page: Int, snippet: String): Boolean

    @Query("DELETE FROM bookmarks WHERE book_id = :bookId AND spine_index = :spineIndex AND page_in_chapter = :page AND snippet = :snippet")
    suspend fun deleteNearWithSnippet(bookId: Long, spineIndex: Int, page: Int, snippet: String)

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId AND spine_index = :spineIndex AND bookmark_type = 0 AND ((page_in_chapter >= 0 AND page_in_chapter = :page) OR (page_in_chapter < 0 AND ABS(scroll_ratio - :ratio) < 0.01)) ORDER BY id DESC LIMIT 1")
    suspend fun findWholePage(bookId: Long, spineIndex: Int, page: Int, ratio: Float): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId AND spine_index = :spineIndex AND page_in_chapter = :page AND bookmark_type = 1 AND snippet = :snippet ORDER BY id DESC LIMIT 1")
    suspend fun findText(bookId: Long, spineIndex: Int, page: Int, snippet: String): BookmarkEntity?
}
