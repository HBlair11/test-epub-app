package com.epubreader.app.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

data class DictionaryEntry(
    val word: String,
    val partOfSpeech: String,
    val definition: String,
)

class OfflineDictionary(private val context: Context) {
    private val dbFile: File
        get() = File(context.filesDir, DB_RELATIVE_PATH)

    suspend fun lookup(input: String): DictionaryEntry? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val normalized = input.trim().lowercase()
            if (normalized.isBlank() || normalized.any { !it.isLetter() && it != '-' }) return@withContext null
            ensureDatabase()
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                queryExact(db, normalized)
                    ?: fallbackCandidates(normalized).firstNotNullOfOrNull { queryExact(db, it) }
            }
        }

    private fun queryExact(db: SQLiteDatabase, word: String): DictionaryEntry? {
        db.query(
            "words",
            arrayOf("word", "pos", "definition"),
            "word = ? COLLATE NOCASE",
            arrayOf(word),
            null,
            null,
            null,
            "1",
        ).use { c ->
            return if (c.moveToFirst()) {
                DictionaryEntry(c.getString(0), c.getString(1), c.getString(2))
            } else null
        }
    }

    private fun fallbackCandidates(word: String): List<String> = buildList {
        if (word.endsWith("ies") && word.length > 4) add(word.dropLast(3) + "y")
        if (word.endsWith("es") && word.length > 3) add(word.dropLast(2))
        if (word.endsWith("s") && word.length > 2) add(word.dropLast(1))
        if (word.endsWith("ing") && word.length > 5) {
            add(word.dropLast(3))
            add(word.dropLast(3) + "e")
        }
        if (word.endsWith("ed") && word.length > 4) {
            add(word.dropLast(2))
            add(word.dropLast(1))
        }
    }.distinct().filter { it != word }

    private fun ensureDatabase() {
        if (dbFile.exists() && dbFile.length() > 0L) return
        dbFile.parentFile?.mkdirs()
        context.assets.open(ASSET_PATH).use { input ->
            FileOutputStream(dbFile).use { output -> input.copyTo(output) }
        }
    }

    companion object {
        private const val ASSET_PATH = "dict/en.db"
        private const val DB_RELATIVE_PATH = "dict/en.db"
    }
}
