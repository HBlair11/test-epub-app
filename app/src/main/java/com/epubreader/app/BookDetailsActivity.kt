package com.epubreader.app

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.epubreader.app.data.AppDatabase
import com.epubreader.app.data.BookEntity
import com.epubreader.app.data.BookRepository
import com.epubreader.app.epub.EpubImporter
import com.epubreader.app.databinding.ActivityBookDetailsBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

class BookDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookDetailsBinding
    private var bookId: Long = -1L
    private var shouldRefreshOnResume = false

    private lateinit var importer: EpubImporter

    // Patch 11 "Screen On" controller
    private lateinit var keepScreenOnController:
            com.epubreader.app.util.KeepScreenOnController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        keepScreenOnController =
            com.epubreader.app.util.KeepScreenOnController(
                this,
                com.epubreader.app.data.PrefsManager(applicationContext)
            )

        binding = ActivityBookDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        importer = EpubImporter(applicationContext)

        setSupportActionBar(binding.toolbar)

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)

        if (bookId < 0) {
            finish()
            return
        }

        load()
    }

    private fun load() {
        lifecycleScope.launch(Dispatchers.IO) {

            val book = AppDatabase
                .get(applicationContext)
                .bookDao()
                .getById(bookId)
                ?: return@launch

            withContext(Dispatchers.Main) {
                bind(book)
            }
        }
    }

    private fun bind(book: BookEntity) {

        // =========================
        // TITLE
        // =========================

        title = book.title
        binding.title.text = book.title

        // =========================
        // COVER
        // =========================

        book.coverPath?.let { coverPath ->

            Glide.with(this)
                .load(File(coverPath))
                .into(binding.cover)
        }

        // =========================
        // BOOK
        // =========================

        binding.bookValue.text = book.title

        // =========================
        // AUTHOR
        // =========================

        binding.authorValue.text =
            book.author.ifBlank {
                getString(R.string.unknown_author)
            }

        // =========================
        // SERIES
        // =========================

        if (!book.series.isNullOrBlank()) {

            binding.seriesContainer.visibility = View.VISIBLE

            binding.seriesValue.text =
                book.seriesIndex?.let { index ->
                    // Check if the float ends in .0
                    val formattedIndex = if (index % 1.0 == 0.0) {
                        index.toInt().toString() // Converts 1.0 -> 1
                    } else {
                        index.toString() // Keeps 2.5 -> 2.5
                    }
                    
                    "${book.series} #$formattedIndex"
                } ?: book.series

        } else {

            binding.seriesContainer.visibility = View.GONE
        }

        // =========================
        // FILENAME
        // =========================

        binding.filenameValue.text =
            book.sourceFilename
                ?: File(book.path).name

        // =========================
        // FILE SIZE
        // =========================

        binding.filesizeValue.text =
            Formatter.formatFileSize(
                this,
                book.fileSize
            )

        // =========================
        // LANGUAGE
        // =========================

        binding.languageValue.text =
            book.language?.takeIf {
                it.isNotBlank()
            } ?: ""

        // =========================
        // DATE ADDED
        // =========================

        binding.dateAddedValue.text =
            fmtDate(book.addedDate)

        // =========================
        // LAST READ + BOOKMARK
        // =========================

        if (book.lastOpenedDate != null) {

            binding.lastReadContainer.visibility =
                View.VISIBLE

            val progressText =
                if (book.progress >= 0.995f) {
                    getString(R.string.progress_completed)
                } else {
                    "${(book.progress * 100).toInt()}% read"
                }

            binding.lastReadValue.text =
                "${fmtDate(book.lastOpenedDate!!)} • $progressText"

        } else {

            binding.lastReadContainer.visibility =
                View.GONE
        }

        // =========================
        // DESCRIPTION
        // =========================

        if (!book.description.isNullOrBlank()) {

            binding.descriptionContainer.visibility =
                View.VISIBLE

            binding.description.text =
                book.description

        } else {

            binding.descriptionContainer.visibility =
                View.GONE
        }

        // =========================
        // READ
        // =========================

        binding.btnRead.setOnClickListener {
            shouldRefreshOnResume = true

            startActivity(
                Intent(
                    this,
                    ReaderActivity::class.java
                ).putExtra(
                    ReaderActivity.EXTRA_BOOK_ID,
                    book.id
                )
            )
        }

        // =========================
        // FAVORITE
        // =========================

        binding.btnFavorite.setImageResource(
            if (book.isFavorite) {
                R.drawable.ic_favorite
            } else {
                R.drawable.ic_favorite_border
            }
        )

        binding.btnFavorite.setOnClickListener {

            val newFavoriteState = !book.isFavorite

            lifecycleScope.launch(Dispatchers.IO) {

                AppDatabase
                    .get(applicationContext)
                    .bookDao()
                    .setFavorite(book.id, newFavoriteState)

                withContext(Dispatchers.Main) {
                    binding.btnFavorite.setImageResource(
                        if (newFavoriteState) {
                            R.drawable.ic_favorite
                        } else {
                            R.drawable.ic_favorite_border
                        }
                    )
                }
            }
        }

        // =========================
        // REMOVE FROM READING
        // =========================

        binding.btnRemoveReading.setOnClickListener {

            lifecycleScope.launch(Dispatchers.IO) {

                AppDatabase
                    .get(applicationContext)
                    .bookDao()
                    .clearCurrentlyReading(book.id)
            }

            Snackbar.make(
                binding.root,
                R.string.option_remove_reading,
                Snackbar.LENGTH_SHORT
            ).show()
        }


        // =========================
        // REMOVE FROM LIBRARY
        // =========================

        // REMOVE FROM LIBRARY
        binding.btnRemove.setOnClickListener {
            confirmDeleteBook(book)
        }
    }

    private fun confirmDeleteBook(book: BookEntity) {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.option_remove)
            .setMessage(
                getString(R.string.option_remove) + ": " + book.title
            )
            .setPositiveButton(R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    importer.deleteImported(book)
                    BookRepository(applicationContext).deleteBook(book)

                    withContext(Dispatchers.Main) {
                        finish()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun fmtDate(ts: Long): String =
        DateFormat
            .getMediumDateFormat(this)
            .format(Date(ts))


    // Patch 11 "Screen On" lifecycle hooks.

    override fun onResume() {
        super.onResume()
        keepScreenOnController.onResume()

        if (shouldRefreshOnResume) {
            shouldRefreshOnResume = false
            load()
        }
    }

    override fun onPause() {
        super.onPause()
        keepScreenOnController.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        keepScreenOnController.bump()
    }


    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }
}
