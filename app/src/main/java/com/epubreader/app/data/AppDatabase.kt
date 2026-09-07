package com.epubreader.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, BookmarkEntity::class, CollectionEntity::class, BookCollectionRef::class],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun collectionDao(): CollectionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE books ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE books ADD COLUMN source_filename TEXT")
                }
            }

        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // source_last_modified: cached mtime of the original file, used to
                    // skip re-parsing unchanged books on rescan.
                    database.execSQL("ALTER TABLE books ADD COLUMN source_last_modified INTEGER NOT NULL DEFAULT 0")
                }
            }

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // page_map_csv: cached synthetic "book page" map (ADE byte-mapping).
                    // Font/layout-independent, computed once on import. Nullable because
                    // pre-existing books have no cached map yet (computed lazily on
                    // first open). See EpubPageMap.
                    database.execSQL("ALTER TABLE books ADD COLUMN page_map_csv TEXT")
                }
            }

        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE books ADD COLUMN is_currently_reading INTEGER NOT NULL DEFAULT 0".trimIndent())
                    database.execSQL("UPDATE books SET is_currently_reading = 1 WHERE last_opened_date IS NOT NULL".trimIndent())
                }
            }


        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE books ADD COLUMN source_uri TEXT")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_books_source_uri ON books(source_uri)")
                }
            }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "epub.db",
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
