package com.epubreader.app.epub

import com.epubreader.app.data.BookEntity

/** Pure, conservative matching rules shared by import/metadata-refresh tests. */
object BookImportIdentity {
    data class Lookup(
        val byChecksum: Map<String, BookEntity>,
        val bySourceUri: Map<String, BookEntity>,
        val byIdentifier: Map<String, List<BookEntity>>,
        val byFilename: Map<String, List<BookEntity>>,
    ) {
        companion object {
            fun fromBooks(books: List<BookEntity>): Lookup = Lookup(
                byChecksum = books.associateBy { it.checksum },
                bySourceUri = books.filter { !it.sourceUri.isNullOrBlank() }.associateBy { it.sourceUri!! },
                byIdentifier = books
                    .filter { !it.identifier.isNullOrBlank() }
                    .groupBy { it.identifier!!.trim() },
                byFilename = books
                    .filter { !it.sourceFilename.isNullOrBlank() }
                    .groupBy { it.sourceFilename!! },
            )
        }
    }

    fun match(
        lookup: Lookup,
        sourceUri: String?,
        checksum: String?,
        identifier: String?,
        filename: String?,
    ): BookEntity? {
        checksum?.takeIf { it.isNotBlank() }?.let { value ->
            lookup.byChecksum[value]?.let { return it }
        }
        sourceUri?.takeIf { it.isNotBlank() }?.let { value ->
            lookup.bySourceUri[value]?.let { return it }
        }
        identifier?.trim()?.ifBlank { null }?.let { value ->
            val matches = lookup.byIdentifier[value].orEmpty()
            if (matches.size == 1) return matches.first()
        }
        filename?.takeIf { it.isNotBlank() }?.let { value ->
            val matches = lookup.byFilename[value].orEmpty()
            if (matches.size == 1) {
                val candidate = matches.first()
                val candidateIdentifier = candidate.identifier?.trim()?.ifBlank { null }
                val normalizedIdentifier = identifier?.trim()?.ifBlank { null }
                if (normalizedIdentifier == null || candidateIdentifier == null || candidateIdentifier == normalizedIdentifier) {
                    return candidate
                }
            }
        }
        return null
    }
}
