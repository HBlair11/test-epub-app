package com.epubreader.app

import com.epubreader.app.util.SystemBarController

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.result.contract.ActivityResultContracts
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

    private val coverPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { applyPickedCover(it) }
    }

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
        SystemBarController.apply(this)

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
            book.language?.takeIf { it.isNotBlank() } ?: ""
        binding.publishYearValue.text = book.publishYear?.toString().orEmpty()
        binding.subjectsValue.text = book.subjectTags.orEmpty()

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

            lifecycleScope.launch(Dispatchers.IO) {
                AppDatabase.get(applicationContext).bookDao().markOpened(book.id, System.currentTimeMillis())
            }
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

        binding.btnEditDetails.setOnClickListener { showEditDialog(book) }

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

    private fun showEditDialog(book: BookEntity) {
        val view = layoutInflater.inflate(R.layout.dialog_book_edit, null)
        val titleEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.titleEdit)
        val authorEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.authorEdit)
        val seriesEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.seriesEdit)
        val seriesIndexEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.seriesIndexEdit)
        val publisherEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.publisherEdit)
        val yearEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.yearEdit)
        val languageEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.languageEdit)
        val identifierEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.identifierEdit)
        val subjectsEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.subjectsEdit)
        val descriptionEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.descriptionEdit)

        titleEdit.setText(book.title)
        authorEdit.setText(book.author)
        seriesEdit.setText(book.series.orEmpty())
        seriesIndexEdit.setText(book.seriesIndex?.toString().orEmpty())
        publisherEdit.setText(book.publisher.orEmpty())
        yearEdit.setText(book.publishYear?.toString().orEmpty())
        languageEdit.setText(book.language.orEmpty())
        identifierEdit.setText(book.identifier.orEmpty())
        subjectsEdit.setText(book.subjectTags.orEmpty())
        descriptionEdit.setText(book.description.orEmpty())

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.edit_book_details)
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.edit_book_cover, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newTitle = titleEdit.text?.toString()?.trim().orEmpty().ifBlank { getString(R.string.untitled) }
                val newAuthor = authorEdit.text?.toString()?.trim().orEmpty()
                val newSeries = seriesEdit.text?.toString()?.trim().orEmpty().ifBlank { null }
                val newSeriesIndex = seriesIndexEdit.text?.toString()?.trim()?.toDoubleOrNull()
                val newPublisher = publisherEdit.text?.toString()?.trim().orEmpty().ifBlank { null }
                val newYear = yearEdit.text?.toString()?.trim()?.toIntOrNull()
                val newLanguage = languageEdit.text?.toString()?.trim().orEmpty().ifBlank { null }
                val newIdentifier = identifierEdit.text?.toString()?.trim().orEmpty().ifBlank { null }
                val newSubjects = subjectsEdit.text?.toString()?.trim().orEmpty().ifBlank { null }
                val newDescription = descriptionEdit.text?.toString()?.trim().orEmpty().ifBlank { null }
                lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase.get(applicationContext).bookDao().updateMetadataOnly(
                        id = book.id, title = newTitle, author = newAuthor, series = newSeries, seriesIndex = newSeriesIndex,
                        language = newLanguage, publisher = newPublisher, description = newDescription, identifier = newIdentifier,
                        publishYear = newYear, subjectTags = newSubjects, sourceUri = book.sourceUri, sourceFilename = book.sourceFilename,
                        sortTitle = newTitle, sortAuthor = newAuthor,
                    )
                    withContext(Dispatchers.Main) {
                        Snackbar.make(binding.root, R.string.details_saved, Snackbar.LENGTH_SHORT).show()
                        load()
                    }
                }
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                coverPicker.launch(arrayOf("image/*"))
            }
        }
        dialog.show()
    }

    private fun applyPickedCover(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val book = AppDatabase.get(applicationContext).bookDao().getById(bookId) ?: return@launch
            val dir = File(filesDir, "covers").apply { mkdirs() }
            val target = File(dir, "${book.id}_manual_${System.currentTimeMillis()}.png")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bitmap = BitmapFactory.decodeStream(input) ?: return@launch
                    target.outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, out) }
                    bitmap.recycle()
                } ?: return@launch
                val old = book.coverPath
                AppDatabase.get(applicationContext).bookDao().update(book.copy(coverPath = target.absolutePath, metadataEdited = true))
                if (!old.isNullOrBlank() && old != target.absolutePath && old.contains(File(filesDir, "covers").absolutePath)) File(old).delete()
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root, R.string.details_saved, Snackbar.LENGTH_SHORT).show()
                    load()
                }
            } catch (_: Exception) {
                target.delete()
            }
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
