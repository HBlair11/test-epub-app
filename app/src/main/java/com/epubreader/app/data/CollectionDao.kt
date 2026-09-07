package com.epubreader.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(collection: CollectionEntity): Long

    @Query("SELECT id FROM collections WHERE name = :name")
    suspend fun getIdByName(name: String): Long?

    @Delete
    suspend fun delete(collection: CollectionEntity)

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query(
        "SELECT b.* FROM books b INNER JOIN book_collection_ref r ON b.id = r.book_id " +
                "WHERE r.collection_id = :collectionId ORDER BY b.sort_title COLLATE NOCASE"
    )
    fun observeBooksInCollection(collectionId: Long): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBook(ref: BookCollectionRef)

    @Query("DELETE FROM book_collection_ref WHERE book_id = :bookId AND collection_id = :collectionId")
    suspend fun removeBook(bookId: Long, collectionId: Long)

    @Query("SELECT c.* FROM collections c INNER JOIN book_collection_ref r ON c.id = r.collection_id WHERE r.book_id = :bookId ORDER BY c.name COLLATE NOCASE")
    fun observeCollectionsForBook(bookId: Long): Flow<List<CollectionEntity>>
}
