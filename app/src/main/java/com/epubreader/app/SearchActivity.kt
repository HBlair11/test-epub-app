package com.epubreader.app

import com.epubreader.app.util.SystemBarController

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.epubreader.app.data.BookEntity
import com.epubreader.app.data.BookRepository
import com.epubreader.app.databinding.ActivitySearchBinding
import com.epubreader.app.ui.BookAdapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var repo: BookRepository
    private val adapter =
        BookAdapter(grid = false, onClick = { openBook(it) }, onLongClick = { false }, onDetails = { openDetails(it) })

    private val query = MutableStateFlow("")

    override fun onCreate(savedInstanceState: Bundle?) {
        repo = BookRepository(applicationContext)
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarController.apply(this)
        setSupportActionBar(binding.searchToolbar)
        binding.searchToolbar.setNavigationOnClickListener { finish() }

        binding.searchRecycler.layoutManager = LinearLayoutManager(this)
        binding.searchRecycler.adapter = adapter

        binding.searchEdit.requestFocus()
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query.value = s?.toString()?.trim() ?: ""
            }
        })

        lifecycleScope.launch {
            query
                .debounce(180)
                .distinctUntilChanged()
                .flatMapLatest { q -> if (q.isBlank()) flowOf(emptyList()) else repo.search(q) }
                .collectLatest { results -> showResults(results) }
        }
    }

    private fun showResults(books: List<BookEntity>) {
        adapter.submitList(books)
        if (books.isEmpty()) {
            binding.searchEmpty.visibility = View.VISIBLE
            binding.searchEmptyText.text =
                if (query.value.isBlank()) getString(R.string.search_hint)
                else getString(R.string.empty_library)
        } else {
            binding.searchEmpty.visibility = View.GONE
        }
    }

    private fun openBook(book: BookEntity) {
        lifecycleScope.launch { repo.markOpened(book.id) }
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id))
    }

    private fun openDetails(book: BookEntity) {
        startActivity(
            Intent(this, BookDetailsActivity::class.java).putExtra(
                BookDetailsActivity.EXTRA_BOOK_ID,
                book.id
            )
        )
    }
}
