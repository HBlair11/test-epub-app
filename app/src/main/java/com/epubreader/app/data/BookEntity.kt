package com.epubreader.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [
        Index(value = ["checksum"], unique = true),
        Index(value = ["source_uri"], unique = true),
    ],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "author") val author: String,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "cover_path") val coverPath: String? = null,
    @ColumnInfo(name = "progress") val progress: Float = 0f,
    @ColumnInfo(name = "series") val series: String? = null,
    @ColumnInfo(name = "series_index") val seriesIndex: Double? = null,
    @ColumnInfo(name = "language") val language: String? = null,
    @ColumnInfo(name = "publisher") val publisher: String? = null,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "identifier") val identifier: String? = null,
    @ColumnInfo(name = "publish_year") val publishYear: Int? = null,
    @ColumnInfo(name = "subject_tags") val subjectTags: String? = null,
    @ColumnInfo(name = "metadata_edited") val metadataEdited: Boolean = false,
    @ColumnInfo(name = "is_currently_reading") val isCurrentlyReading: Boolean = false,
    @ColumnInfo(name = "added_date") val addedDate: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "modified_date") val modifiedDate: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_opened_date") val lastOpenedDate: Long? = null,
    @ColumnInfo(name = "file_size") val fileSize: Long = 0L,
    @ColumnInfo(name = "spine_index") val spineIndex: Int = 0,
    @ColumnInfo(name = "scroll_ratio") val scrollRatio: Float = 0f,
    @ColumnInfo(name = "checksum") val checksum: String,
    @ColumnInfo(name = "sort_title") val sortTitle: String = title,
    @ColumnInfo(name = "sort_author") val sortAuthor: String = author,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    /** Stable SAF document URI for the original source file. Used to preserve the
     *  same logical library item when the source EPUB is replaced in-place. */
    @ColumnInfo(name = "source_uri") val sourceUri: String? = null,
    @ColumnInfo(name = "source_filename") val sourceFilename: String? = null,
    /** Source file's last-modified epoch millis — used to skip re-parsing
     *  unchanged files on rescan (size + mtime + name match). */
    @ColumnInfo(name = "source_last_modified") val sourceLastModified: Long = 0L,
    /** Legacy ADE byte-map retained for schema/backward compatibility. The active
     *  reader no longer uses this synthetic page model. */
    @ColumnInfo(name = "page_map_csv") val pageMapCsv: String? = null,
    /** Screen-accurate page counts measured for the current reader layout. */
    @ColumnInfo(name = "screen_page_map_csv") val screenPageMapCsv: String? = null,
    /** Layout fingerprint for [screenPageMapCsv], so cached pages are reused only
     *  when the reader viewport/settings match the measurement. */
    @ColumnInfo(name = "screen_page_layout_key") val screenPageLayoutKey: String? = null,
)
