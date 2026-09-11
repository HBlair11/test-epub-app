package com.epubreader.app.epub

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale

/**
 * Local-only dictionary backed by the bundled SQLite dataset in assets/dict/
 * (WordNet-derived for English; additional languages can be dropped in as
 * dict/<lang>.db files with the same schema).
 *
 * Patch v37 lookup pipeline:
 *  1. Normalize the raw selection (trim whitespace, strip surrounding
 *     punctuation/quotes — including typographic quotes — collapse spaces,
 *     lowercase).
 *  2. Multi-word selections are queried as whole phrases first so idioms
 *     ("kick the bucket") resolve; a phrase miss falls back to the longest
 *     meaningful word in the selection.
 *  3. Single-word lookups walk an irregular-lemma chain: exact match ->
 *     bundled irregular-form table (mice->mouse, went->go, ...) ->
 *     rule-based stemming (tenses, plurals, comparatives) -> each candidate.
 *  4. On a total miss, a "did you mean" list is built from same-prefix
 *     candidates filtered by Levenshtein distance.
 *
 * No network access is ever used. The bundled asset is copied to app-private
 * storage on first use and refreshed when the bundled asset version
 * (PRAGMA user_version) is newer than the installed copy.
 */
class DictionaryLookup(context: Context, language: String = DEFAULT_LANGUAGE) : AutoCloseable {

    data class Entry(val word: String, val partOfSpeech: String, val definition: String)

    data class Result(
        val entries: List<Entry>,
        val matchedWord: String?,
        val suggestions: List<String>,
    )

    private val appContext = context.applicationContext
    private val languageTag: String =
        language.substringBefore('-').substringBefore('_')
            .lowercase(Locale.US).ifBlank { DEFAULT_LANGUAGE }
    private val dbFile: File = File(appContext.filesDir, "dictionary-$languageTag.db")
    private val db: SQLiteDatabase

    init {
        ensureInstalled()
        db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    /** Full lookup for a raw selection (single word or phrase). */
    fun lookup(raw: String): Result {
        val phrase = normalize(raw)
        if (phrase.isBlank()) return Result(emptyList(), null, emptyList())

        exact(phrase).takeIf { it.isNotEmpty() }?.let { return Result(it, phrase, emptyList()) }

        if (phrase.contains(' ')) {
            // Idiom attempt: lemmatize each word so "kicks the bucket" finds
            // "kick the bucket" in the database.
            lemmatizePhrase(phrase)?.let { lemma ->
                exact(lemma).takeIf { it.isNotEmpty() }?.let { return Result(it, lemma, emptyList()) }
            }
            // Phrase miss: fall back to the longest meaningful word.
            val focus = phrase.split(' ').filter { it.length > 2 }.maxByOrNull { it.length } ?: phrase
            val single = lookupSingle(focus)
            if (single.entries.isNotEmpty()) return single
            val suggestions = suggest(phrase).ifEmpty { suggest(focus) }
            return Result(emptyList(), null, suggestions)
        }

        return lookupSingle(phrase)
    }

    /** "Did you mean" candidates for a (misspelled) word or phrase. */
    fun suggest(raw: String): List<String> {
        val word = normalize(raw)
        if (word.isBlank()) return emptyList()
        if (word.contains(' ')) {
            val focus = word.split(' ').filter { it.length > 2 }.maxByOrNull { it.length } ?: word
            return suggest(focus)
        }
        val threshold = when {
            word.length <= 4 -> 1
            word.length <= 7 -> 2
            else -> 3
        }
        val pattern = if (word.length >= 2) word.substring(0, 2) + "%" else word.substring(0, 1) + "%"
        val candidates = mutableListOf<String>()
        db.rawQuery(
            "SELECT DISTINCT word FROM words WHERE word LIKE ? ORDER BY word LIMIT ?",
            arrayOf(pattern, SUGGEST_SCAN_LIMIT.toString()),
        ).use { cursor ->
            while (cursor.moveToNext() && candidates.size < MAX_SUGGESTIONS) {
                val candidate = cursor.getString(0)
                if (candidate == word) continue
                val distance = levenshtein(word, candidate)
                if (distance <= threshold && distance < candidate.length) {
                    candidates += candidate
                }
            }
        }
        return candidates.sortedBy { levenshtein(word, it) }.take(MAX_SUGGESTIONS)
    }

    /** True when this instance's dictionary language matches the given tag
     *  (compares the primary subtag only: "pt-BR" matches a "pt" db). */
    fun matchesLanguage(language: String?): Boolean {
        val other = language?.substringBefore('-')?.substringBefore('_')
            ?.lowercase(Locale.US).orEmpty()
        return other.isBlank() && languageTag == DEFAULT_LANGUAGE || other == languageTag
    }

    override fun close() = db.close()

    // ------------------------------------------------------------ internals

    private fun lookupSingle(word: String): Result {
        exact(word).takeIf { it.isNotEmpty() }?.let { return Result(it, word, emptyList()) }
        for (candidate in lemmaCandidates(word)) {
            if (candidate == word) continue
            exact(candidate).takeIf { it.isNotEmpty() }?.let { return Result(it, candidate, emptyList()) }
        }
        return Result(emptyList(), null, suggest(word))
    }

    private fun exact(word: String): List<Entry> =
        db.rawQuery(
            "SELECT word, pos, definition FROM words WHERE word = ? COLLATE NOCASE ORDER BY rowid LIMIT ?",
            arrayOf(word, MAX_ENTRIES.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }

    /** All plausible base forms of a word: bundled irregular table first, then rules. */
    private fun lemmaCandidates(word: String): List<String> {
        val out = LinkedHashSet<String>()
        irregularBase(word)?.let { out += it }
        out += ruleStems(word)
        return out.toList()
    }

    private fun irregularBase(word: String): String? =
        db.rawQuery(
            "SELECT base FROM lemmas WHERE word = ? COLLATE NOCASE LIMIT 1",
            arrayOf(word),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /** Rule-based stemming candidates (tense, plural, comparative). */
    private fun ruleStems(word: String): List<String> {
        val out = mutableListOf<String>()
        val w = word.replace("'", "")
        fun add(candidate: String) {
            if (candidate.length >= MIN_STEM_LENGTH && candidate != w) out += candidate
        }
        when {
            w.endsWith("ies") && w.length > 4 -> add(w.dropLast(3) + "y")
            w.endsWith("ing") && w.length > 5 -> {
                val stem = w.dropLast(3)
                add(stem)
                if (stem.length >= 2 && stem.last() == stem[stem.length - 2]) add(stem.dropLast(1))
                add(stem + "e")
                // doubled-e verbs: "freed" -> "free"
                if (stem.endsWith("e")) add(stem.dropLast(1))
            }
            w.endsWith("ied") && w.length > 4 -> add(w.dropLast(3) + "y")
            w.endsWith("ed") && w.length > 4 -> {
                val stem = w.dropLast(2)
                add(stem)
                if (stem.length >= 2 && stem.last() == stem[stem.length - 2]) add(stem.dropLast(1))
                add(stem + "e")
                if (stem.endsWith("i")) add(stem.dropLast(1) + "y")
            }
            w.endsWith("es") && w.length > 4 -> {
                val stem = w.dropLast(2)
                add(stem)
                if (stem.endsWith("sh") || stem.endsWith("ch") || stem.endsWith("s") || stem.endsWith("x") || stem.endsWith("z")) add(stem)
                if (stem.endsWith("i")) add(stem.dropLast(1) + "y")
            }
            w.endsWith("est") && w.length > 5 -> add(w.dropLast(3))
            w.endsWith("er") && w.length > 4 -> {
                val stem = w.dropLast(2)
                add(stem)
                if (stem.length >= 2 && stem.last() == stem[stem.length - 2]) add(stem.dropLast(1))
            }
            w.endsWith("s") && w.length > 3 -> {
                add(w.dropLast(1))
                add(w.dropLast(1) + "e")
            }
        }
        return out
    }

    /** Lemmatize every word of a phrase ("kicks the bucket" -> "kick the bucket"). */
    private fun lemmatizePhrase(phrase: String): String? {
        val words = phrase.split(' ')
        if (words.size < 2) return null
        val lemmatized = words.map { word ->
            irregularBase(word) ?: ruleStems(word).firstOrNull { it.length >= MIN_STEM_LENGTH } ?: word
        }
        val result = lemmatized.joinToString(" ")
        return result.takeIf { it != phrase }
    }

    private fun normalize(raw: String): String =
        raw.trim()
            .replace(CHAR_QUOTES, "")
            .replace(Regex("^[^\\p{L}']+|[^\\p{L}']+$"), "")
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.US)

    private fun ensureInstalled() {
        val currentVersion = runCatching { readUserVersion(dbFile) }.getOrDefault(0)
        if (dbFile.exists() && currentVersion >= ASSET_VERSION) return
        val assetPath = resolveAssetPath() ?: return
        val tmp = File(dbFile.parentFile, dbFile.name + ".tmp")
        runCatching {
            appContext.assets.open(assetPath).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            // Only replace an older copy; never downgrade a newer local file.
            val installed = runCatching { readUserVersion(dbFile) }.getOrDefault(0)
            if (!dbFile.exists() || installed < ASSET_VERSION) {
                if (dbFile.exists()) dbFile.delete()
                if (!tmp.renameTo(dbFile)) {
                    tmp.copyTo(dbFile, overwrite = true)
                    tmp.delete()
                }
            } else {
                tmp.delete()
            }
        }
    }

    private fun resolveAssetPath(): String? {
        val dir = appContext.assets.list("dict").orEmpty()
        return when {
            dir.contains("$languageTag.db") -> "dict/$languageTag.db"
            dir.contains("$DEFAULT_LANGUAGE.db") -> "dict/$DEFAULT_LANGUAGE.db"
            else -> null
        }
    }

    private fun readUserVersion(file: File): Int {
        if (!file.exists()) return 0
        return SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            .use { db -> db.version }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val swap = prev; prev = cur; cur = swap
        }
        return prev[b.length]
    }

    companion object {
        const val DEFAULT_LANGUAGE = "en"

        /** Bundled asset revision; bump to force a refresh of installed copies. */
        private const val ASSET_VERSION = 2
        private const val MAX_ENTRIES = 6
        private const val MAX_SUGGESTIONS = 6
        private const val SUGGEST_SCAN_LIMIT = 400
        private const val MIN_STEM_LENGTH = 3
        private val CHAR_QUOTES = charArrayOf('"', '\u201C', '\u201D', '\u2018', '\u2019')
    }
}
