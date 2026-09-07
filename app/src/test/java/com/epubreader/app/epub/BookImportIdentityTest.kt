package com.epubreader.app.epub

import com.epubreader.app.data.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookImportIdentityTest {
    private fun book(
        id: Long,
        checksum: String,
        uri: String? = null,
        filename: String? = null,
        identifier: String? = null,
    ) = BookEntity(
        id = id,
        title = "Book $id",
        author = "Author",
        path = "/tmp/$id.epub",
        checksum = checksum,
        sourceUri = uri,
        sourceFilename = filename,
        identifier = identifier,
    )

    @Test
    fun `checksum wins over every other identity`() {
        val books = listOf(
            book(1, "a", "uri-a", "same.epub", "id-a"),
            book(2, "b", "uri-b", "same.epub", "id-b"),
        )
        val lookup = BookImportIdentity.Lookup.fromBooks(books)
        assertEquals(2L, BookImportIdentity.match(lookup, "uri-a", "b", "id-a", "same.epub")?.id)
    }

    @Test
    fun `stable source uri identifies replaced content`() {
        val books = listOf(book(7, "old", "content://book/7", "Book.epub"))
        val lookup = BookImportIdentity.Lookup.fromBooks(books)
        assertEquals(7L, BookImportIdentity.match(lookup, "content://book/7", "new", null, "Book.epub")?.id)
    }

    @Test
    fun `ambiguous identifier is skipped`() {
        val books = listOf(book(1, "a", identifier = "isbn"), book(2, "b", identifier = "isbn"))
        assertNull(BookImportIdentity.match(BookImportIdentity.Lookup.fromBooks(books), null, "new", "isbn", "Book.epub"))
    }

    @Test
    fun `ambiguous filename is skipped`() {
        val books = listOf(book(1, "a", filename = "Book.epub"), book(2, "b", filename = "Book.epub"))
        assertNull(BookImportIdentity.match(BookImportIdentity.Lookup.fromBooks(books), null, "new", null, "Book.epub"))
    }
}
