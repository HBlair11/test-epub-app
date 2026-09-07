package com.epubreader.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.epubreader.app.data.BookEntity
import com.epubreader.app.databinding.ItemBookGridBinding
import com.epubreader.app.databinding.ItemBookListBinding

class BookAdapter(
    val grid: Boolean,
    private val onClick: (BookEntity) -> Unit,
    private val onLongClick: (BookEntity) -> Boolean,
    private val onDetails: (BookEntity) -> Unit = { onClick(it) }
) : ListAdapter<BookEntity, RecyclerView.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (grid) {
            BookGridHolder(ItemBookGridBinding.inflate(inflater, parent, false))
        } else {
            BookListHolder(ItemBookListBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val book = getItem(position)
        when (holder) {
            is BookGridHolder -> holder.bind(book)
            is BookListHolder -> holder.bind(book)
        }
    }

    inner class BookGridHolder(private val b: ItemBookGridBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            itemView.setOnClickListener { onClick(getItem(bindingAdapterPosition)) }
            itemView.setOnLongClickListener { onLongClick(getItem(bindingAdapterPosition)) }
        }

        fun bind(book: BookEntity) {
            b.title.text = book.title
            b.author.text =
                book.author.ifBlank {
                    itemView.context.getString(com.epubreader.app.R.string.unknown_author)
                }

            loadCover(book, b.cover)

            // Clamp progress to a safe 0.0 - 1.0 range.
            val progress = book.progress.coerceIn(0f, 1f)
            val pct = (progress * 100).toInt()

            // Progress badge
            if (progress >= 0.90f) {
                b.progressBadge.visibility = View.VISIBLE
                b.progressBadge.text = "✓"
            } else if (progress > 0f) {
                b.progressBadge.visibility = View.VISIBLE
                b.progressBadge.text = "$pct%"
            } else {
                b.progressBadge.visibility = View.GONE
            }

            // Progress bar
            if (progress > 0f) {
                b.progressTrack.visibility = View.VISIBLE
                b.progressFill.visibility = View.VISIBLE

                // The fill is always the full track width.
                // scaleX visually represents the percentage read.
                b.progressFill.scaleX = progress
                b.progressFill.pivotX = 0f
                b.progressFill.pivotY = 0.5f
            } else {
                b.progressTrack.visibility = View.GONE
                b.progressFill.visibility = View.GONE

                // Reset recycled ViewHolder state.
                b.progressFill.scaleX = 0f
            }
        }
    }

    inner class BookListHolder(private val b: ItemBookListBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnClickListener { onClick(getItem(bindingAdapterPosition)) }
            b.root.setOnLongClickListener { onLongClick(getItem(bindingAdapterPosition)) }
            b.infoArea.setOnClickListener { onDetails(getItem(bindingAdapterPosition)) }
        }

        fun bind(book: BookEntity) {
            b.title.text = book.title
            b.author.text =
                book.author.ifBlank { itemView.context.getString(com.epubreader.app.R.string.unknown_author) }
            if (!book.series.isNullOrBlank()) {
                b.series.visibility = View.VISIBLE
                b.series.text = book.seriesIndex?.let { index ->
                    val formattedIndex = if (index % 1.0 == 0.0) {
                        index.toInt().toString()
                    } else {
                        index.toString()
                    }

                    "${book.series} #$formattedIndex"
                } ?: book.series
            } else {
                b.series.visibility = View.GONE
            }
            val pct = (book.progress * 100).toInt()
            b.progressBar.progress = pct
            if (book.progress >= 0.90f) {
                b.completedBadge.visibility = View.VISIBLE
                b.progressPct.visibility = View.GONE
                b.progressBar.visibility = View.GONE
            } else {
                b.completedBadge.visibility = View.GONE
                b.progressPct.visibility = View.VISIBLE
                b.progressBar.visibility = View.VISIBLE
                b.progressPct.text = "$pct%"
            }
            loadCover(book, b.cover)
        }
    }

    private fun loadCover(book: BookEntity, view: android.widget.ImageView) {
        val coverPath = book.coverPath
        if (coverPath != null) {
            Glide.with(view).load(java.io.File(coverPath))
                .centerCrop()
                .placeholder(com.epubreader.app.R.drawable.cover_frame)
                .into(view)
        } else {
            Glide.with(view).clear(view)
            view.setImageResource(com.epubreader.app.R.drawable.cover_frame)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BookEntity>() {
            override fun areItemsTheSame(o: BookEntity, n: BookEntity) = o.id == n.id
            override fun areContentsTheSame(o: BookEntity, n: BookEntity) = o == n
        }
    }
}
