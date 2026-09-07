package com.epubreader.app.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    @Test
    fun `parses EPUB 3 series metadata and basic book structure`() {
        val file = createTestEpub(
            opf = """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Test Book</dc:title>
                    <dc:creator>Test Author</dc:creator>
                    <dc:identifier id="book-id">urn:test:book</dc:identifier>
                    <dc:language>en</dc:language>
                    <meta property="belongs-to-collection" id="series-1">Test Series</meta>
                    <meta refines="#series-1" property="group-position">1</meta>
                    <meta refines="#series-1" property="collection-type">series</meta>
                  </metadata>
                  <manifest>
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml" />
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav" />
                  </manifest>
                  <spine>
                    <itemref idref="chapter" />
                  </spine>
                </package>
            """.trimIndent(),
            files = mapOf(
                "chapter.xhtml" to "<html><body><h1>Chapter One</h1><p>Elizabeth reads a book.</p></body></html>",
                "nav.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml"><body>
                      <nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops"><ol><li><a href="chapter.xhtml">Chapter One</a></li></ol></nav>
                    </body></html>
                """.trimIndent(),
            ),
        )

        val book = EpubParser().parse(file)
        assertEquals("Test Book", book.metadata.title)
        assertEquals("Test Author", book.metadata.authorString)
        assertEquals("Test Series", book.metadata.series)
        assertEquals(1.0, book.metadata.seriesIndex)
        assertEquals(1, book.spine.size)
        assertEquals(1, book.toc.size)
        assertEquals("Chapter One", book.toc.first().label)
        assertNotNull(book.toc.first().href)
    }

    @Test
    fun `calibre series has priority over EPUB 3 series`() {
        val file = createTestEpub(
            opf = """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Priority Test</dc:title>
                    <meta name="calibre:series" content="Calibre Series" />
                    <meta name="calibre:series_index" content="2" />
                    <meta property="belongs-to-collection" id="s">EPUB3 Series</meta>
                    <meta refines="#s" property="collection-type">series</meta>
                    <meta refines="#s" property="group-position">9</meta>
                  </metadata>
                  <manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml" /></manifest>
                  <spine><itemref idref="c" /></spine>
                </package>
            """.trimIndent(),
            files = mapOf("chapter.xhtml" to "<html><body>Text</body></html>"),
        )

        val metadata = EpubParser().parse(file).metadata
        assertEquals("Calibre Series", metadata.series)
        assertEquals(2.0, metadata.seriesIndex)
    }

    @Test
    fun `legacy series fills missing calibre index`() {
        val file = createTestEpub(
            opf = """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Mixed Metadata</dc:title>
                    <meta name="calibre:series" content="Calibre Series" />
                    <meta name="series_index" content="3" />
                    <meta property="belongs-to-collection" id="s">EPUB3 Series</meta>
                    <meta refines="#s" property="collection-type">series</meta>
                    <meta refines="#s" property="group-position">9</meta>
                  </metadata>
                  <manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml" /></manifest>
                  <spine><itemref idref="c" /></spine>
                </package>
            """.trimIndent(),
            files = mapOf("chapter.xhtml" to "<html><body>Text</body></html>"),
        )

        val metadata = EpubParser().parse(file).metadata
        assertEquals("Calibre Series", metadata.series)
        assertEquals(3.0, metadata.seriesIndex)
    }

    private fun createTestEpub(opf: String, files: Map<String, String>): File {
        val file = File.createTempFile("livre_parser_test_", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml" /></rootfiles>
                </container>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(opf.toByteArray())
            zip.closeEntry()

            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry("OEBPS/$name"))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        file.deleteOnExit()
        assertTrue(file.exists())
        return file
    }
}
