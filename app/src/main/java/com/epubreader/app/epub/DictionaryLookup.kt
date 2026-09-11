package com.epubreader.app.epub

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** Local-only dictionary backed by a bundled, project-authored SQLite dataset. */
class DictionaryLookup(context: Context) : AutoCloseable {
    data class Entry(val word: String, val partOfSpeech: String, val definition: String)

    private val db = DictionaryDb(context.applicationContext).writableDatabase

    fun lookup(raw: String): List<Entry> {
        val word = raw.trim()
            .lowercase()
            .replace(Regex("^[^a-z]+|[^a-z']+$"), "")
        if (word.isBlank()) return emptyList()
        exact(word).takeIf { it.isNotEmpty() }?.let { return it }
        val lemma = baseForm(word)
        return if (lemma != null && lemma != word) exact(lemma) else emptyList()
    }

    override fun close() = db.close()

    private fun exact(word: String): List<Entry> =
        db.rawQuery(
            "SELECT word, pos, definition FROM words WHERE word = ? COLLATE NOCASE ORDER BY rowid",
            arrayOf(word),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }

    private fun baseForm(word: String): String? {
        val irregular = mapOf(
            "mice" to "mouse", "men" to "man", "women" to "woman", "children" to "child",
            "geese" to "goose", "feet" to "foot", "teeth" to "tooth", "people" to "person",
            "better" to "good",
            "best" to "good", "worse" to "bad", "worst" to "bad", "was" to "be", "were" to "be",
            "did" to "do", "done" to "do", "went" to "go", "gone" to "go",
        )
        irregular[word]?.let { return it }
        return when {
            word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
            word.endsWith("ing") && word.length > 5 -> {
                val stem = word.dropLast(3)
                when {
                    stem.endsWith("e") -> stem
                    stem.length >= 2 && stem.last() == stem[stem.length - 2] -> stem.dropLast(1)
                    else -> stem
                }
            }
            word.endsWith("ed") && word.length > 4 -> {
                val stem = word.dropLast(2)
                if (stem.endsWith("i")) stem.dropLast(1) + "y" else stem
            }
            word.endsWith("es") && word.length > 4 -> word.dropLast(2)
            word.endsWith("s") && word.length > 3 -> word.dropLast(1)
            else -> null
        }
    }

    private class DictionaryDb(context: Context) :
        SQLiteOpenHelper(context, File(context.filesDir, "dictionary-en.db").absolutePath, null, 1) {

        init {
            val file = File(context.filesDir, "dictionary-en.db")
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                context.assets.open("dict/en.db").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS words (word TEXT NOT NULL COLLATE NOCASE, pos TEXT NOT NULL, definition TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_words_word ON words(word COLLATE NOCASE)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
