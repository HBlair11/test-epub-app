package com.epubreader.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseSmokeTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sourceUriAndReadingStateRoundTrip() {
        val id = db.bookDao().insert(
            BookEntity(
                title = "Database Test",
                author = "Test Author",
                path = "/tmp/test.epub",
                checksum = "database-test-checksum",
                sourceUri = "content://test/book/1",
                sourceFilename = "Database Test.epub",
                isFavorite = true,
                isCurrentlyReading = true,
                progress = 0.42f,
                screenPageMapCsv = "4,7,3",
                screenPageLayoutKey = "test-layout",
            )
        )

        val loaded = db.bookDao().getBySourceUri("content://test/book/1")
        assertEquals(id, loaded?.id)
        assertEquals(0.42f, loaded?.progress ?: 0f, 0.0001f)
        assertEquals(true, loaded?.isFavorite)
        assertEquals(true, loaded?.isCurrentlyReading)
        assertEquals("4,7,3", loaded?.screenPageMapCsv)
        assertEquals("test-layout", loaded?.screenPageLayoutKey)
    }
}
