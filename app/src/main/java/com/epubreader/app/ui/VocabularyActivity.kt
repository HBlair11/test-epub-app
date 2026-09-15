package com.epubreader.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.epubreader.app.R
import com.epubreader.app.data.AppDatabase
import com.epubreader.app.data.DictionaryHistoryEntity
import com.epubreader.app.databinding.ActivityVocabularyBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Patch v37: offline vocabulary builder — every word successfully looked up
 * with Define in the reader is saved (once) to the dictionary_history table
 * and listed here. Supports per-word delete, clear-all, and CSV / Markdown
 * exports (shared via the app's FileProvider) for Anki-style study.
 */
class VocabularyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVocabularyBinding
    private lateinit var adapter: VocabularyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVocabularyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        adapter = VocabularyAdapter { item -> deleteWord(item) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnExportCsv.setOnClickListener { export(CSV) }
        binding.btnExportMarkdown.setOnClickListener { export(MARKDOWN) }
        binding.btnClearAll.setOnClickListener { confirmClearAll() }

        AppDatabase.get(this).dictionaryHistoryDao().observeAll().asLiveData().observe(this) { list ->
            adapter.submitList(list)
            binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            binding.actionsRow.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun deleteWord(item: DictionaryHistoryEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.get(applicationContext).dictionaryHistoryDao().delete(item)
        }
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.vocabulary_clear_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase.get(applicationContext).dictionaryHistoryDao().clear()
                }
                Snackbar.make(binding.root, R.string.vocabulary_cleared, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun export(format: Int) {
        val words = adapter.currentList
        if (words.isEmpty()) return
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val (fileName, mime, content) = when (format) {
            MARKDOWN -> Triple(
                "vocabulary-$timestamp.md",
                "text/markdown",
                buildMarkdown(words),
            )
            else -> Triple(
                "vocabulary-$timestamp.csv",
                "text/csv",
                buildCsv(words),
            )
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val file = File(cacheDir, fileName)
            file.writeText(content, Charsets.UTF_8)
            withContext(Dispatchers.Main) {
                share(file, mime)
                Snackbar.make(binding.root, R.string.vocabulary_exported, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /** CSV: word,part of speech,definition — imported directly by Anki. */
    private fun buildCsv(words: List<DictionaryHistoryEntity>): String = buildString {
        append("word,part of speech,definition\n")
        words.forEach { item ->
            append(csvCell(item.word)).append(',')
                .append(csvCell(item.partOfSpeech.orEmpty())).append(',')
                .append(csvCell(item.definition.orEmpty())).append('\n')
        }
    }

    private fun csvCell(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /** Markdown table, handy for notes apps and printable study sheets. */
    private fun buildMarkdown(words: List<DictionaryHistoryEntity>): String = buildString {
        append("# Vocabulary\n\n")
        append("| Word | Definition |\n")
        append("| --- | --- |\n")
        words.forEach { item ->
            append("| **")
                .append(item.word.replace("|", "\\|"))
                .append("** | ")
                .append(item.definition.orEmpty().replace("|", "\\|").replace("\n", " "))
                .append(" |\n")
        }
    }

    private fun share(file: File, mime: String) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.vocabulary_title)))
    }

    companion object {
        private const val CSV = 0
        private const val MARKDOWN = 1
    }
}
