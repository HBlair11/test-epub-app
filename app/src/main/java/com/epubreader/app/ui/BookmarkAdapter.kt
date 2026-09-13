package com.epubreader.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.data.BookmarkEntity
import com.epubreader.app.databinding.ItemBookmarkBinding
import java.text.DateFormat
import java.util.Date

class BookmarkAdapter(
    private val onDelete: (BookmarkEntity) -> Unit,
    private val onClick: (BookmarkEntity) -> Unit
) : ListAdapter<BookmarkEntity, BookmarkAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        holder.itemView.setOnClickListener { onClick(item) }
        holder.b.btnDelete.setOnClickListener { onDelete(item) }
    }

    inner class VH(val b: ItemBookmarkBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: BookmarkEntity) {
            val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.createdAt))
            if (item.bookmarkType == BookmarkEntity.TYPE_WHOLE_PAGE) {
                val wholePageNumber = currentList.count {
                    it.bookmarkType == BookmarkEntity.TYPE_WHOLE_PAGE &&
                        (it.createdAt < item.createdAt ||
                            (it.createdAt == item.createdAt && it.id <= item.id))
                }.coerceAtLeast(1)
                b.title.text = "Bookmark $wholePageNumber"
            } else {
                b.title.text = item.snippet.ifBlank { item.chapterTitle }
            }
            b.subtitle.text = "${item.chapterTitle} · $date"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BookmarkEntity>() {
            override fun areItemsTheSame(o: BookmarkEntity, n: BookmarkEntity) = o.id == n.id
            override fun areContentsTheSame(o: BookmarkEntity, n: BookmarkEntity) = o == n
        }
    }
}
