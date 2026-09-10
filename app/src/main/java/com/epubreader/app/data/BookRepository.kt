package com.epubreader.app.data

import android.content.Context
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.flow.Flow

class BookRepository(
    context: Context,
) {
    private val db = AppDatabase.get(context)
    private val bookDao = db.bookDao()
    private val bookmarkDao = db.bookmarkDao()
    private val collectionDao = db.collectionDao()

    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeAll()

    fun observeCurrentlyReading(): Flow<List<BookEntity>> = bookDao.observeCurrentlyReading()

    suspend fun setCurrentlyReading(id: Long) = bookDao.setCurrentlyReading(id)

    suspend fun markOpened(id: Long, openedAt: Long = System.currentTimeMillis()) =
        bookDao.markOpened(id, openedAt)

    suspend fun updateCurrentLocation(id: Long, location: String?) =
        bookDao.updateCurrentLocation(id, location)

    suspend fun clearCurrentlyReading(id: Long) = bookDao.clearCurrentlyReading(id)

    fun observeFavorites(): Flow<List<BookEntity>> = bookDao.observeFavorites()

    fun observeFinished(): Flow<List<BookEntity>> = bookDao.observeFinished()

    fun observeToBeRead(): Flow<List<BookEntity>> = bookDao.observeToBeRead()

    fun observeLastOpened(): Flow<BookEntity?> = bookDao.observeLastOpened()

    // Patch 16 (Issue #3): books for the "Recently Added" temp screen.
    fun observeByIds(ids: List<Long>): Flow<List<BookEntity>> =
        if (ids.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList()) else bookDao.observeByIds(ids)

    suspend fun setFavorite(
        id: Long,
        fav: Boolean,
    ) = bookDao.setFavorite(id, fav)

    fun observeAuthors(): Flow<List<GroupedRow>> = bookDao.observeAuthors()

    fun observeSeries(): Flow<List<GroupedRow>> = bookDao.observeSeries()

    fun observeByAuthor(author: String): Flow<List<BookEntity>> = bookDao.observeByAuthor(author)

    fun observeBySeries(series: String): Flow<List<BookEntity>> = bookDao.observeBySeries(series)

    fun search(query: String): Flow<List<BookEntity>> = bookDao.search(query)

    suspend fun getBook(id: Long): BookEntity? = bookDao.getById(id)

    suspend fun getAllBooks(): List<BookEntity> = bookDao.getAllBooks()

    suspend fun getByChecksum(checksum: String): BookEntity? = bookDao.getByChecksum(checksum)

    suspend fun updateProgress(
        id: Long,
        progress: Float,
        spineIndex: Int,
        scrollRatio: Float,
    ) {
        bookDao.updateProgress(id, progress, spineIndex, scrollRatio, System.currentTimeMillis())
    }

    suspend fun deleteBook(book: BookEntity) = bookDao.delete(book)

    // ---- Bookmarks ----
    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> = bookmarkDao.observeForBook(bookId)

    suspend fun addBookmark(b: BookmarkEntity): Long = bookmarkDao.insert(b)

    suspend fun deleteBookmark(b: BookmarkEntity) = bookmarkDao.delete(b)

    suspend fun bookmarkExistsNear(
        bookId: Long,
        spineIndex: Int,
        ratio: Float,
    ): Boolean = bookmarkDao.existsNear(bookId, spineIndex, ratio)

    // ---- Collections ----
    fun observeCollections(): Flow<List<CollectionEntity>> = collectionDao.observeAll()

    fun observeBooksInCollection(id: Long): Flow<List<BookEntity>> = collectionDao.observeBooksInCollection(id)

    fun observeCollectionsForBook(bookId: Long): Flow<List<CollectionEntity>> =
        collectionDao.observeCollectionsForBook(bookId)

    suspend fun createCollection(name: String): Long {
        collectionDao.getIdByName(name)?.let { return it }
        return collectionDao.insert(CollectionEntity(name = name.trim()))
    }

    suspend fun deleteCollection(c: CollectionEntity) = collectionDao.delete(c)

    suspend fun renameCollection(
        id: Long,
        name: String,
    ) = collectionDao.rename(id, name)

    suspend fun addBookToCollection(
        bookId: Long,
        collectionId: Long,
    ) = collectionDao.addBook(BookCollectionRef(bookId, collectionId))

    suspend fun removeBookFromCollection(
        bookId: Long,
        collectionId: Long,
    ) = collectionDao.removeBook(bookId, collectionId)
}
