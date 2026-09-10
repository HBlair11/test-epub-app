package com.epubreader.app.epub

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.epubreader.app.data.AppDatabase
import com.epubreader.app.data.BookEntity
import com.epubreader.app.data.SourceFingerprint
import java.io.File
import java.security.MessageDigest

/**
 * Scans a SAF folder for EPUB files and imports each into the library:
 * copies the file into app-private storage (fully offline), parses metadata,
 * extracts the cover, and upserts a Room row keyed by content checksum.
 */
class EpubImporter(
    private val context: Context,
) {
    /** Result of an import: the book id (null on failure) and whether it was newly added. */
    data class ImportResult(
        val bookId: Long?,
        val isNew: Boolean,
    )

    /** A scanned source file with the metadata needed to fingerprint it, gathered
     *  in a single bulk cursor pass (not per-file DocumentFile calls). */
    data class ScannedFile(
        val uri: Uri,
        val name: String,
        val size: Long,
        val lastModified: Long,
    )

    /** A fully prepared (copied + parsed + cover-extracted) book entity that has
     *  NOT yet been written to the database. The folder scanner prepares every
     *  new/changed book first, then commits them all in one transaction so the
     *  library list refreshes once at the end instead of once per book. */
    data class PreparedImport(
        val entity: BookEntity,
        val isNew: Boolean,
    )

    enum class MetadataRefreshStatus {
        UPDATED,
        UNCHANGED,
        SKIPPED,
        FAILED,
    }

    data class MetadataRefreshItem(
        val status: MetadataRefreshStatus,
        val bookId: Long?,
        val displayName: String,
        val author: String,
        val detail: String,
    )

    data class MetadataRefreshResult(
        val items: List<MetadataRefreshItem>,
    ) {
        val updated: Int
            get() = items.count { it.status == MetadataRefreshStatus.UPDATED }

        val unchanged: Int
            get() = items.count { it.status == MetadataRefreshStatus.UNCHANGED }

        val skipped: Int
            get() = items.count { it.status == MetadataRefreshStatus.SKIPPED }

        val failed: Int
            get() = items.count { it.status == MetadataRefreshStatus.FAILED }
    }

    private data class MetadataUpdate(
        val id: Long,
        val title: String,
        val author: String,
        val series: String?,
        val seriesIndex: Double?,
        val language: String?,
        val publisher: String?,
        val description: String?,
        val identifier: String?,
        val publishYear: Int?,
        val subjectTags: String?,
        val sourceUri: String?,
        val sourceFilename: String?,
        val sortTitle: String,
        val sortAuthor: String,
    )

    /** ReadEra-style fast folder listing: walks the SAF tree using ONE cursor
     *  query per directory that returns name + size + mtime + mime_type for every
     *  child in a single pass. This avoids the per-file DocumentFile.name() /
     *  length() / lastModified() calls (each a separate SAF cursor query) that
     *  made scanning a folder of 250–300 books take minutes instead of seconds.
     *  Only new/changed files returned here are later fully imported. */
    fun listEpubFiles(treeUri: Uri): List<ScannedFile> {
        val out = mutableListOf<ScannedFile>()
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return out
        val rootDocId =
            try {
                android.provider.DocumentsContract.getDocumentId(root.uri)
            } catch (_: Exception) {
                return out
            }
        walkChildrenBulk(treeUri, rootDocId, out)
        return out
    }

    private fun walkChildrenBulk(
        treeUri: Uri,
        parentDocId: String,
        out: MutableList<ScannedFile>,
    ) {
        val childrenUri =
            try {
                android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            } catch (_: Exception) {
                return
            }
        val cursor =
            try {
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_SIZE,
                        android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null,
                    null,
                    null,
                )
            } catch (_: Exception) {
                return
            }
        cursor?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val size = if (c.isNull(2)) 0L else c.getLong(2)
                val mtime = if (c.isNull(3)) 0L else c.getLong(3)
                val mime = c.getString(4) ?: ""
                if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkChildrenBulk(treeUri, docId, out)
                } else if (BookFileTypes.isBookFile(name)) {
                    val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    out.add(ScannedFile(fileUri, name, size, mtime))
                }
            }
        }
    }

    /** Loads every existing book's rescan fingerprint into an in-memory map keyed
     *  by source filename — a single DB query instead of one per scanned file. */
    suspend fun sourceFingerprintMap(): Map<String, List<SourceFingerprint>> {
        val list = db.bookDao().getSourceFingerprints()
        val map = HashMap<String, MutableList<SourceFingerprint>>(list.size)
        for (fp in list) {
            // A filename can repeat (duplicate filenames across subfolders);
            // collect all of them so the scanner can skip only if ANY match.
            map.getOrPut(fp.sourceFilename) { mutableListOf() }.add(fp)
        }
        return map
    }

    private val db = AppDatabase.get(context)
    private val parser = EpubParser()

    private val epubDir = File(context.filesDir, "epubs").apply { mkdirs() }
    private val coverDir = File(context.filesDir, "covers").apply { mkdirs() }

    private fun metadataChanged(
        existing: BookEntity,
        title: String,
        author: String,
        series: String?,
        seriesIndex: Double?,
        language: String?,
        publisher: String?,
        description: String?,
        identifier: String?,
    ): Boolean {

        fun normalize(value: String?): String? =
            value
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.ifBlank { null }

        return normalize(existing.title) != normalize(title) ||
                normalize(existing.author) != normalize(author) ||
                normalize(existing.series) != normalize(series) ||
                existing.seriesIndex != seriesIndex ||
                normalize(existing.language) != normalize(language) ||
                normalize(existing.publisher) != normalize(publisher) ||
                normalize(existing.description) != normalize(description) ||
                normalize(existing.identifier) != normalize(identifier)
    }

    /** Imports a single EPUB from a content/file [uri]. Returns the book id, or null on failure. */
    suspend fun importUri(uri: Uri): Long? = importUriResult(uri).bookId

    /** Imports a single EPUB from a content/file [uri], reporting whether it was newly added. */
    suspend fun importUriResult(uri: Uri): ImportResult {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Many ACTION_VIEW providers grant only a temporary read permission.
            // The cached EPUB remains usable; a future rescan can still re-import
            // it if the provider grants access again.
        }
        val doc = DocumentFile.fromSingleUri(context, uri)
        return importDocument(
            uri = uri,
            sourceName = doc?.name,
            sourceSize = doc?.length() ?: 0L,
            sourceLastModified = doc?.lastModified() ?: 0L,
        )
    }

    /** Imports a single EPUB from a content/file [uri] with known source metadata.
     *
     *  Rescan fast-path: if a book already exists for [sourceName] with the same
     *  [sourceSize] + [sourceLastModified] AND its cached epub file is still on
     *  disk, the file is unchanged — skip the copy + checksum + parse entirely.
     *  Only new or modified files pay the full import cost, so repeated scans of a
     *  large folder finish in seconds instead of minutes. */
    suspend fun importDocument(
        uri: Uri,
        sourceName: String?,
        sourceSize: Long,
        sourceLastModified: Long,
    ): ImportResult {
        val sourceUri = uri.toString()
        val identityMatch = db.bookDao().getBySourceUri(sourceUri)

        // Fast skip when the exact SAF document is unchanged. This is the primary
        // identity path; filename is only a legacy fallback for books imported
        // before source_uri existed.
        if (identityMatch != null && sourceSize > 0L &&
            RescanDecision.shouldSkip(
                existingFileSize = identityMatch.fileSize,
                existingMtime = identityMatch.sourceLastModified,
                sourceSize = sourceSize,
                sourceMtime = sourceLastModified,
                cachedFileExists = File(identityMatch.path).exists(),
            )
        ) {
            if (identityMatch.sourceFilename != sourceName ||
                identityMatch.fileSize != sourceSize ||
                identityMatch.sourceLastModified != sourceLastModified
            ) {
                db.bookDao().updateSourceIdentity(
                    id = identityMatch.id,
                    sourceUri = sourceUri,
                    sourceFilename = sourceName,
                    fileSize = sourceSize,
                    sourceLastModified = sourceLastModified,
                )
            }
            return ImportResult(identityMatch.id, false)
        }

        if (identityMatch == null && !sourceName.isNullOrBlank() && sourceSize > 0L) {
            val legacyMatches = db.bookDao().getAllBySourceFilename(sourceName)
            if (legacyMatches.size == 1) {
                val cached = legacyMatches.first()
                if (RescanDecision.shouldSkip(
                        existingFileSize = cached.fileSize,
                        existingMtime = cached.sourceLastModified,
                        sourceSize = sourceSize,
                        sourceMtime = sourceLastModified,
                        cachedFileExists = File(cached.path).exists(),
                    )
                ) {
                    return ImportResult(cached.id, false)
                }
            }
        }

        val resolver = context.contentResolver
        val tempFile = File(epubDir, "import_${System.currentTimeMillis()}.epub")
        try {
            resolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { input.copyTo(it) }
            } ?: return ImportResult(null, false)
        } catch (e: Exception) {
            tempFile.delete()
            return ImportResult(null, false)
        }
        return importFileResult(
            file = tempFile,
            deleteSource = true,
            sourceFilename = sourceName,
            sourceSize = sourceSize,
            sourceLastModified = sourceLastModified,
            sourceUri = sourceUri,
        )
    }

    /** Re-parses every EPUB in the selected folder without replacing cached content or
     * changing reading state. Matching is conservative and all existing-book lookup
     * data is loaded once so the refresh does not issue three Room queries per file. */
    suspend fun refreshMetadata(
        files: List<ScannedFile>,
    ): MetadataRefreshResult {
        if (files.isEmpty()) return MetadataRefreshResult(emptyList())

        val allBooks = db.bookDao().getAllBooks()
        val identityLookup = BookImportIdentity.Lookup.fromBooks(allBooks)
        val results = mutableListOf<MetadataRefreshItem>()
        val updates = mutableListOf<MetadataUpdate>()

        for (source in files) {
            var tempFile: File? = null
            try {
                tempFile = File.createTempFile("metadata_refresh_", ".epub", epubDir)
                val copied = context.contentResolver.openInputStream(source.uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false

                if (!copied) {
                    results += MetadataRefreshItem(
                        MetadataRefreshStatus.FAILED, null, source.name, "",
                        context.getString(com.epubreader.app.R.string.metadata_refresh_detail_read_failed)
                    )
                    continue
                }

                val sourceChecksum = checksum(tempFile)
                val parsed = try { parser.parse(tempFile) } catch (_: Exception) { null }
                if (parsed == null) {
                    results += MetadataRefreshItem(
                        MetadataRefreshStatus.FAILED, null, source.name, "",
                        context.getString(com.epubreader.app.R.string.metadata_refresh_detail_parse_failed)
                    )
                    continue
                }

                val identifier = parsed.metadata.identifiers.firstOrNull()?.trim()?.ifBlank { null }

                // Matching hierarchy is centralized and tested: checksum → stable
                // source URI → unique identifier → safe filename fallback.
                val existing = BookImportIdentity.match(
                    lookup = identityLookup,
                    sourceUri = source.uri.toString(),
                    checksum = sourceChecksum,
                    identifier = identifier,
                    filename = source.name,
                )

                if (existing == null) {
                    results += MetadataRefreshItem(
                        MetadataRefreshStatus.SKIPPED, null, source.name, parsed.metadata.authorString,
                        context.getString(com.epubreader.app.R.string.metadata_refresh_detail_no_safe_match)
                    )
                    continue
                }

                val title = parsed.metadata.title.ifBlank { source.name.substringBeforeLast('.') }
                val author = parsed.metadata.authorString
                val series = parsed.metadata.series
                val seriesIndex = parsed.metadata.seriesIndex
                val language = parsed.metadata.language
                val publisher = parsed.metadata.publisher
                val description = parsed.metadata.description
                val changed = metadataChanged(
                    existing, title, author, series, seriesIndex, language, publisher, description, identifier
                )
                val contentDiffers = existing.checksum != sourceChecksum

                if (existing.metadataEdited) {
                    // User-edited metadata is authoritative. A parser refresh may
                    // observe new source metadata, but it must never overwrite the
                    // user's curated values. Source identity is still updated below.
                    db.bookDao().updateSourceIdentity(
                        existing.id,
                        source.uri.toString(),
                        source.name,
                        source.size,
                        source.lastModified,
                    )
                    results += MetadataRefreshItem(
                        MetadataRefreshStatus.SKIPPED,
                        existing.id,
                        source.name,
                        existing.author,
                        context.getString(com.epubreader.app.R.string.metadata_refresh_detail_user_edited),
                    )
                    continue
                }

                updates += MetadataUpdate(
                    existing.id, title, author, series, seriesIndex, language, publisher,
                    description, identifier, parsePublishYear(parsed.metadata.publishDate),
                    parsed.metadata.subjects.joinToString(", ").ifBlank { null },
                    source.uri.toString(), source.name, title, author
                )

                val detail = when {
                    changed && contentDiffers -> context.getString(com.epubreader.app.R.string.metadata_refresh_detail_updated_content_differs)
                    changed -> context.getString(com.epubreader.app.R.string.metadata_refresh_detail_updated)
                    contentDiffers -> context.getString(com.epubreader.app.R.string.metadata_refresh_detail_unchanged_content_differs)
                    else -> context.getString(com.epubreader.app.R.string.metadata_refresh_detail_unchanged)
                }

                results += MetadataRefreshItem(
                    if (changed) MetadataRefreshStatus.UPDATED else MetadataRefreshStatus.UNCHANGED,
                    existing.id, source.name, author, detail
                )
            } catch (_: Exception) {
                results += MetadataRefreshItem(
                    MetadataRefreshStatus.FAILED, null, source.name, "",
                    context.getString(com.epubreader.app.R.string.metadata_refresh_detail_failed)
                )
            } finally {
                tempFile?.delete()
            }
        }

        if (updates.isNotEmpty()) {
            db.withTransaction {
                for (update in updates) {
                    db.bookDao().updateMetadataFromParser(
                        id = update.id,
                        title = update.title,
                        author = update.author,
                        series = update.series,
                        seriesIndex = update.seriesIndex,
                        language = update.language,
                        publisher = update.publisher,
                        description = update.description,
                        identifier = update.identifier,
                        publishYear = update.publishYear,
                        subjectTags = update.subjectTags,
                        sourceUri = update.sourceUri,
                        sourceFilename = update.sourceFilename,
                        sortTitle = update.sortTitle,
                        sortAuthor = update.sortAuthor,
                    )
                }
            }
        }

        return MetadataRefreshResult(results)
    }

    /** Imports an EPUB [file] that already lives on disk, reporting whether it was newly added. */
    suspend fun importFileResult(
        file: File,
        deleteSource: Boolean = false,
        sourceFilename: String? = null,
        sourceSize: Long = 0L,
        sourceLastModified: Long = 0L,
        sourceUri: String? = null,
    ): ImportResult {
        var working = file
        val checksum = checksum(working)
        val target = File(epubDir, "$checksum.epub")
        if (!target.exists()) {
            // Move/copy into dedupe location.
            if (deleteSource && file.absolutePath.startsWith(epubDir.absolutePath).not()) {
                file.copyTo(target, overwrite = true)
                file.delete()
            } else {
                file.copyTo(target, overwrite = true)
            }
        }
        working = target

        val parsed =
            try {
                parser.parse(working)
            } catch (e: Exception) {
                return ImportResult(null, false)
            }

        val coverFile = File(coverDir, "$checksum.png")
        if (!coverFile.exists()) {
            val extracted = CoverExtractor.extract(working, parsed, coverFile)
            if (extracted == null) {
                CoverGenerator.generate(
                    parsed.metadata.title.ifBlank { working.nameWithoutExtension },
                    parsed.metadata.authorString,
                    coverFile,
                )
            }
        }

        val existingByUri = sourceUri?.let { db.bookDao().getBySourceUri(it) }
        val existingByChecksum = db.bookDao().getByChecksum(checksum)
        val existing = existingByUri ?: existingByChecksum
        val entity =
            BookEntity(
                id = existing?.id ?: 0,
                title = existing?.takeIf { it.metadataEdited }?.title
                    ?: parsed.metadata.title.ifBlank { working.nameWithoutExtension },
                author = existing?.takeIf { it.metadataEdited }?.author
                    ?: parsed.metadata.authorString,
                path = working.absolutePath,
                coverPath = existing?.takeIf { it.metadataEdited }?.coverPath
                    ?: if (coverFile.exists()) coverFile.absolutePath else null,
                progress = existing?.progress ?: 0f,
                series = existing?.takeIf { it.metadataEdited }?.series ?: parsed.metadata.series,
                seriesIndex = existing?.takeIf { it.metadataEdited }?.seriesIndex ?: parsed.metadata.seriesIndex,
                language = existing?.takeIf { it.metadataEdited }?.language ?: parsed.metadata.language,
                publisher = existing?.takeIf { it.metadataEdited }?.publisher ?: parsed.metadata.publisher,
                description = existing?.takeIf { it.metadataEdited }?.description ?: parsed.metadata.description,
                identifier = existing?.takeIf { it.metadataEdited }?.identifier ?: parsed.metadata.identifiers.firstOrNull(),
                publishYear = existing?.takeIf { it.metadataEdited }?.publishYear ?: parsePublishYear(parsed.metadata.publishDate),
                subjectTags = existing?.takeIf { it.metadataEdited }?.subjectTags ?: parsed.metadata.subjects.joinToString(", ").ifBlank { null },
                metadataEdited = existing?.metadataEdited ?: false,
                addedDate = existing?.addedDate ?: System.currentTimeMillis(),
                modifiedDate = System.currentTimeMillis(),
                lastOpenedDate = existing?.lastOpenedDate,
                fileSize = working.length(),
                spineIndex = existing?.spineIndex ?: 0,
                spineCount = parsed.spine.size,
                chapterCount = EpubChapterDetector.detect(parsed).size,
                chapterIndex = existing?.chapterIndex ?: 0,
                scrollRatio = existing?.scrollRatio ?: 0f,
                checksum = checksum,
                sortTitle = existing?.takeIf { it.metadataEdited }?.sortTitle
                    ?: parsed.metadata.title.ifBlank { working.nameWithoutExtension },
                sortAuthor = existing?.takeIf { it.metadataEdited }?.sortAuthor
                    ?: parsed.metadata.authorString,
                isFavorite = existing?.isFavorite ?: false,
                isCurrentlyReading = existing?.isCurrentlyReading ?: false,
                sourceUri = sourceUri ?: existing?.sourceUri,
                sourceFilename = sourceFilename ?: existing?.sourceFilename ?: working.name,
                sourceLastModified = if (sourceLastModified > 0L) sourceLastModified else existing?.sourceLastModified
                    ?: 0L,
                // Preserve the legacy ADE map for schema/backward compatibility.
                pageMapCsv = existing?.pageMapCsv,
                screenPageMapCsv = existing?.takeIf { it.checksum == checksum }?.screenPageMapCsv,
                screenPageLayoutKey = existing?.takeIf { it.checksum == checksum }?.screenPageLayoutKey,
            )
        val id =
            if (existing != null) {
                db.bookDao().update(entity)
                existing.id
            } else {
                db.bookDao().insert(entity)
            }
        return ImportResult(id, existing == null)
    }

    /** Prepares a book for import (copy into dedupe location, checksum, parse,
     *  extract cover) WITHOUT writing to the database. The folder scanner calls
     *  this for every new/changed file, then commits them all in one transaction
     *  via [commitImport] so the library list refreshes once at the end. */
    suspend fun prepareImport(
        uri: Uri,
        sourceName: String?,
        sourceSize: Long,
        sourceLastModified: Long,
    ): PreparedImport? {
        val tempFile = File(epubDir, "import_${System.currentTimeMillis()}_${Thread.currentThread().id}.epub")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { input.copyTo(it) }
            } ?: return null
        } catch (e: Exception) {
            tempFile.delete()
            return null
        }
        val prepared = prepareFromWorking(tempFile, sourceName, sourceSize, sourceLastModified, uri.toString())
        // Content was copied into the checksum-named dedupe target inside
        // prepareFromWorking; the temp file is no longer needed.
        tempFile.delete()
        return prepared
    }

    private suspend fun prepareFromWorking(
        file: File,
        sourceFilename: String?,
        sourceSize: Long,
        sourceLastModified: Long,
        sourceUri: String?,
    ): PreparedImport? {
        var working = file
        val checksum = checksum(working)
        val target = File(epubDir, "$checksum.epub")
        if (!target.exists()) {
            file.copyTo(target, overwrite = true)
        }
        working = target
        val parsed =
            try {
                parser.parse(working)
            } catch (e: Exception) {
                return null
            } ?: return null
        val coverFile = File(coverDir, "$checksum.png")
        if (!coverFile.exists()) {
            val extracted = CoverExtractor.extract(working, parsed, coverFile)
            if (extracted == null) {
                CoverGenerator.generate(
                    parsed.metadata.title.ifBlank { working.nameWithoutExtension },
                    parsed.metadata.authorString,
                    coverFile,
                )
            }
        }
        val existingByUri = sourceUri?.let { db.bookDao().getBySourceUri(it) }
        val existingByChecksum = db.bookDao().getByChecksum(checksum)
        val existing = existingByUri ?: existingByChecksum
        val entity =
            BookEntity(
                id = existing?.id ?: 0,
                title = existing?.takeIf { it.metadataEdited }?.title
                    ?: parsed.metadata.title.ifBlank { working.nameWithoutExtension },
                author = existing?.takeIf { it.metadataEdited }?.author
                    ?: parsed.metadata.authorString,
                path = working.absolutePath,
                coverPath = existing?.takeIf { it.metadataEdited }?.coverPath
                    ?: if (coverFile.exists()) coverFile.absolutePath else null,
                progress = existing?.progress ?: 0f,
                series = existing?.takeIf { it.metadataEdited }?.series ?: parsed.metadata.series,
                seriesIndex = existing?.takeIf { it.metadataEdited }?.seriesIndex ?: parsed.metadata.seriesIndex,
                language = existing?.takeIf { it.metadataEdited }?.language ?: parsed.metadata.language,
                publisher = existing?.takeIf { it.metadataEdited }?.publisher ?: parsed.metadata.publisher,
                description = existing?.takeIf { it.metadataEdited }?.description ?: parsed.metadata.description,
                identifier = existing?.takeIf { it.metadataEdited }?.identifier ?: parsed.metadata.identifiers.firstOrNull(),
                publishYear = existing?.takeIf { it.metadataEdited }?.publishYear ?: parsePublishYear(parsed.metadata.publishDate),
                subjectTags = existing?.takeIf { it.metadataEdited }?.subjectTags ?: parsed.metadata.subjects.joinToString(", ").ifBlank { null },
                metadataEdited = existing?.metadataEdited ?: false,
                addedDate = existing?.addedDate ?: System.currentTimeMillis(),
                modifiedDate = System.currentTimeMillis(),
                lastOpenedDate = existing?.lastOpenedDate,
                fileSize = working.length(),
                spineIndex = existing?.spineIndex ?: 0,
                spineCount = parsed.spine.size,
                chapterCount = EpubChapterDetector.detect(parsed).size,
                chapterIndex = existing?.chapterIndex ?: 0,
                scrollRatio = existing?.scrollRatio ?: 0f,
                checksum = checksum,
                sortTitle = existing?.takeIf { it.metadataEdited }?.sortTitle
                    ?: parsed.metadata.title.ifBlank { working.nameWithoutExtension },
                sortAuthor = existing?.takeIf { it.metadataEdited }?.sortAuthor
                    ?: parsed.metadata.authorString,
                isFavorite = existing?.isFavorite ?: false,
                isCurrentlyReading = existing?.isCurrentlyReading ?: false,
                sourceUri = sourceUri ?: existing?.sourceUri,
                sourceFilename = sourceFilename ?: existing?.sourceFilename ?: working.name,
                sourceLastModified = if (sourceLastModified > 0L) sourceLastModified else existing?.sourceLastModified
                    ?: 0L,
                pageMapCsv = existing?.pageMapCsv,
                screenPageMapCsv = existing?.takeIf { it.checksum == checksum }?.screenPageMapCsv,
                screenPageLayoutKey = existing?.takeIf { it.checksum == checksum }?.screenPageLayoutKey,
            )
        return PreparedImport(entity, existing == null)
    }

    /** Writes a prepared book to the database (insert if new, update if it
     *  already existed by checksum). Designed to be called inside a
     *  `db.withTransaction { }` block so a whole folder's worth of new books is
     *  committed atomically — the library's Room Flow then re-emits once. */
    suspend fun commitImport(prepared: PreparedImport): Long {
        return if (prepared.entity.id != 0L) {
            db.bookDao().update(prepared.entity)
            prepared.entity.id
        } else {
            db.bookDao().insert(prepared.entity)
        }
    }

    /** Commits every prepared book in a single transaction so the library list
     *  refreshes once at the end (no per-book layout thrash while scanning).
     *  Returns the ids of the books that were newly added (Patch 16, Issue #3:
     *  used to drive the "Recently Added" temp screen). */
    suspend fun commitImports(prepared: List<PreparedImport>): List<Long> {
        val newIds = mutableListOf<Long>()
        db.withTransaction {
            for (item in prepared) {
                val id = commitImport(item)
                if (item.isNew && id != 0L) newIds.add(id)
            }
        }
        return newIds
    }

    private fun parsePublishYear(value: String?): Int? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return Regex("(1[5-9]\\d{2}|20\\d{2}|21\\d{2})").find(raw)?.value?.toIntOrNull()
    }

    private fun checksum(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun deleteImported(book: BookEntity) {
        File(book.path).delete()
        book.coverPath?.let { File(it).delete() }
    }
}
