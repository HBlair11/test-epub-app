package com.epubreader.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.epubreader.app.R
import com.epubreader.app.data.BookEntity
import com.epubreader.app.databinding.ItemHomeBookBinding
import java.io.File

/** Small horizontal shelf adapter used only by the dedicated Home screen. */
class HomeBookAdapter(
    private val onClick: (BookEntity) -> Unit,
    private val onLongClick: (BookEntity) -> Boolean,
) : ListAdapter<BookEntity, HomeBookAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemHomeBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemHomeBookBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(getItem(position))
            }
            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                position != RecyclerView.NO_POSITION && onLongClick(getItem(position))
            }
        }

        fun bind(book: BookEntity) {
            binding.title.text = book.title
            binding.author.text = book.author.ifBlank { itemView.context.getString(R.string.unknown_author) }

            if (book.coverPath != null) {
                Glide.with(binding.cover)
                    .load(File(book.coverPath))
                    .centerCrop()
                    .placeholder(R.drawable.cover_frame)
                    .into(binding.cover)
            } else {
                Glide.with(binding.cover).clear(binding.cover)
                binding.cover.setImageResource(R.drawable.cover_frame)
            }

            val progress = book.progress.coerceIn(0f, 1f)
            if (progress >= 0.90f) {
                binding.progressBadge.visibility = View.VISIBLE
                binding.progressBadge.text = if (progress >= 0.995f) "✓" else "${(progress * 100).toInt()}%"
            } else {
                binding.progressBadge.visibility = View.GONE
            }

            if (progress > 0f && progress < 0.90f) {
                binding.progressTrack.visibility = View.VISIBLE
                binding.progressFill.visibility = View.VISIBLE
                binding.progressFill.scaleX = progress
                binding.progressFill.pivotX = 0f
                binding.progressFill.pivotY = 0.5f
            } else {
                binding.progressTrack.visibility = View.GONE
                binding.progressFill.visibility = View.GONE
                binding.progressFill.scaleX = 0f
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BookEntity>() {
            override fun areItemsTheSame(oldItem: BookEntity, newItem: BookEntity) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: BookEntity, newItem: BookEntity) = oldItem == newItem
        }
    }
}
