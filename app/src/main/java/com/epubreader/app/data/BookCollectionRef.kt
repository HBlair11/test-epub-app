package com.epubreader.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "book_collection_ref",
    primaryKeys = ["book_id", "collection_id"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("collection_id")]
)
data class BookCollectionRef(
    @androidx.room.ColumnInfo(name = "book_id") val bookId: Long,
    @androidx.room.ColumnInfo(name = "collection_id") val collectionId: Long
) {
    companion object {
        const val COL_BOOK_ID = "book_id"
        const val COL_COLLECTION_ID = "collection_id"
    }
}
