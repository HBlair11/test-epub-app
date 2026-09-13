package com.epubreader.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("book_id")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "spine_index") val spineIndex: Int,
    @ColumnInfo(name = "scroll_ratio") val scrollRatio: Float,
    /** Exact rendered page when the bookmark was created; -1 for legacy bookmarks. */
    @ColumnInfo(name = "page_in_chapter") val pageInChapter: Int = -1,
    @ColumnInfo(name = "chapter_title") val chapterTitle: String,
    @ColumnInfo(name = "snippet") val snippet: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    /** 0 = whole-page bookmark, 1 = selected-text bookmark. */
    @ColumnInfo(name = "bookmark_type") val bookmarkType: Int = TYPE_WHOLE_PAGE,
) {
    companion object {
        const val TYPE_WHOLE_PAGE = 0
        const val TYPE_TEXT = 1
    }
}
