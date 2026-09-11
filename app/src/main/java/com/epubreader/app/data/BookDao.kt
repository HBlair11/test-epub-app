package com.epubreader.app.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY title")
    fun observeAll(): Flow<List<BookEntity>>

    /** Home shelf: the latest library insertions, newest first. Room id is the stable
     * insertion sequence and remains unchanged when an existing EPUB is rescanned. */
    @Query("SELECT * FROM books ORDER BY id DESC LIMIT 6")
    fun observeHomeRecentlyAdded(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE checksum = :checksum")
    suspend fun getByChecksum(checksum: String): BookEntity?

    /** Fast rescan lookup: a book imported from a source file with this name.
     *  Caller confirms size + mtime match before skipping re-parse. */
    @Query("SELECT * FROM books WHERE source_filename = :name ORDER BY id DESC LIMIT 1")
    suspend fun getBySourceFilename(name: String): BookEntity?

    @Query("SELECT * FROM books WHERE source_uri = :uri LIMIT 1")
    suspend fun getBySourceUri(uri: String): BookEntity?

    @Query("SELECT * FROM books ORDER BY id DESC")
    suspend fun getAllBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE source_filename = :name ORDER BY id DESC")
    suspend fun getAllBySourceFilename(name: String): List<BookEntity>

    @Query("SELECT * FROM books WHERE identifier = :identifier ORDER BY id DESC")
    suspend fun getAllByIdentifier(identifier: String): List<BookEntity>

    /** Loads every book's rescan fingerprint (id, source filename, size, mtime,
     *  cached path) in ONE query so a folder re-scan can check hundreds of files
     *  against an in-memory map instead of issuing one DB query per file. */
    @Query("SELECT id, source_uri, source_filename, file_size, source_last_modified, path FROM books WHERE source_filename IS NOT NULL AND source_filename != ''")
    suspend fun getSourceFingerprints(): List<SourceFingerprint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("UPDATE books SET source_uri = :sourceUri, source_filename = :sourceFilename, file_size = :fileSize, source_last_modified = :sourceLastModified WHERE id = :id")
    suspend fun updateSourceIdentity(
        id: Long,
        sourceUri: String,
        sourceFilename: String?,
        fileSize: Long,
        sourceLastModified: Long,
    )

    @Query("UPDATE books SET spine_count = :spineCount WHERE id = :id")
    suspend fun updateSpineCount(id: Long, spineCount: Int)

    @Query("UPDATE books SET last_opened_date = :lastOpened, is_currently_reading = 1 WHERE id = :id")
    suspend fun markOpened(id: Long, lastOpened: Long)

    @Query("UPDATE books SET current_location = :location WHERE id = :id")
    suspend fun updateCurrentLocation(id: Long, location: String?)

    @Query("UPDATE books SET progress = :progress, spine_index = :spineIndex, scroll_ratio = :scrollRatio, last_opened_date = :lastOpened WHERE id = :id")
    suspend fun updateProgress(
        id: Long,
        progress: Float,
        spineIndex: Int,
        scrollRatio: Float,
        lastOpened: Long
    )

    /** Legacy ADE page map retained for backward compatibility. */
    @Query("UPDATE books SET page_map_csv = :csv WHERE id = :id")
    suspend fun updatePageMap(id: Long, csv: String)

    @Query("UPDATE books SET screen_page_map_csv = :csv, screen_page_layout_key = :layoutKey WHERE id = :id")
    suspend fun updateScreenPageMap(id: Long, csv: String, layoutKey: String)

    @Query(
        """
        UPDATE books SET
            title = :title,
            author = :author,
            series = :series,
            series_index = :seriesIndex,
            language = :language,
            publisher = :publisher,
            description = :description,
            identifier = :identifier,
            publish_year = :publishYear,
            subject_tags = :subjectTags,
            metadata_edited = 1,
            source_uri = :sourceUri,
            source_filename = :sourceFilename,
            sort_title = :sortTitle,
            sort_author = :sortAuthor
        WHERE id = :id
        """
    )
    suspend fun updateMetadataOnly(
        id: Long,
        title: String,
        author: String,
        series: String?,
        seriesIndex: Double?,
        language: String?,
        publisher: String?,
        description: String?,
        identifier: String?,
        publishYear: Int?,
        subjectTags: String?,
        sourceUri: String?,
        sourceFilename: String?,
        sortTitle: String,
        sortAuthor: String,
    )


    @Query(
        """
        UPDATE books SET
            title = :title,
            author = :author,
            series = :series,
            series_index = :seriesIndex,
            language = :language,
            publisher = :publisher,
            description = :description,
            identifier = :identifier,
            publish_year = :publishYear,
            subject_tags = :subjectTags,
            source_uri = :sourceUri,
            source_filename = :sourceFilename,
            sort_title = :sortTitle,
            sort_author = :sortAuthor
        WHERE id = :id
        """
    )
    suspend fun updateMetadataFromParser(
        id: Long,
        title: String,
        author: String,
        series: String?,
        seriesIndex: Double?,
        language: String?,
        publisher: String?,
        description: String?,
        identifier: String?,
        publishYear: Int?,
        subjectTags: String?,
        sourceUri: String?,
        sourceFilename: String?,
        sortTitle: String,
        sortAuthor: String,
    )

    @Query("SELECT author AS name, COUNT(*) AS count FROM books WHERE author != '' GROUP BY author ORDER BY author COLLATE NOCASE")
    fun observeAuthors(): Flow<List<GroupedRow>>

    @Query("SELECT series AS name, COUNT(*) AS count FROM books WHERE series IS NOT NULL AND series != '' GROUP BY series ORDER BY series COLLATE NOCASE")
    fun observeSeries(): Flow<List<GroupedRow>>

    @Query("SELECT * FROM books WHERE author = :author ORDER BY series_index IS NULL, series_index, sort_title COLLATE NOCASE")
    fun observeByAuthor(author: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE series = :series ORDER BY series_index IS NULL, series_index, sort_title COLLATE NOCASE")
    fun observeBySeries(series: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE is_currently_reading = 1 ORDER BY last_opened_date DESC, id DESC")
    fun observeCurrentlyReading(): Flow<List<BookEntity>>

    @Query("UPDATE books SET is_currently_reading = 1 WHERE id = :id")
    suspend fun setCurrentlyReading(id: Long)

    @Query("UPDATE books SET is_currently_reading = 0 WHERE id = :id")
    suspend fun clearCurrentlyReading(id: Long)

    @Query("UPDATE books SET is_favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("SELECT * FROM books WHERE last_opened_date IS NOT NULL ORDER BY last_opened_date DESC LIMIT 1")
    fun observeLastOpened(): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE is_favorite = 1 ORDER BY title COLLATE NOCASE")
    fun observeFavorites(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE progress >= 0.995 ORDER BY last_opened_date DESC")
    fun observeFinished(): Flow<List<BookEntity>>

    @Query("SELECT COUNT(*) FROM books WHERE progress >= 0.995")
    suspend fun getFinishedCount(): Int

    @Query("SELECT * FROM books WHERE last_opened_date IS NULL ORDER BY added_date DESC")
    fun observeToBeRead(): Flow<List<BookEntity>>

    // Patch 16 (Issue #3): books matching an explicit id set, ordered by added
    // date descending so the newest import appears first in the "Recently
    // Added" temp screen. Kept here (not in a separate @Query) so it reuses the
    // same DAO transaction path as the other observe* methods.
    @Query("SELECT * FROM books WHERE id IN (:ids) ORDER BY added_date DESC, sort_title COLLATE NOCASE")
    fun observeByIds(ids: List<Long>): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE title LIKE '%' || :q || '%' OR author LIKE '%' || :q || '%' OR series LIKE '%' || :q || '%' ORDER BY sort_title COLLATE NOCASE")
    fun search(q: String): Flow<List<BookEntity>>
}
