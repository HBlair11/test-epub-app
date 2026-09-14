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

    private var wholePageNumbers: Map<Long, Int> = emptyMap()

    override fun submitList(list: List<BookmarkEntity>?) {
        val ordered = list.orEmpty()
            .filter { it.bookmarkType == BookmarkEntity.TYPE_WHOLE_PAGE }
            .sortedWith(compareBy<BookmarkEntity> { it.createdAt }.thenBy { it.id })
        wholePageNumbers = ordered.mapIndexed { index, item -> item.id to (index + 1) }.toMap()
        super.submitList(list)
    }

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
            b.title.text = if (item.bookmarkType == BookmarkEntity.TYPE_WHOLE_PAGE) {
                "Bookmark ${wholePageNumbers[item.id] ?: 1}"
            } else {
                selectedTextForDisplay(item.snippet).ifBlank { item.chapterTitle }
            }
            val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.createdAt))
            b.subtitle.text = "${item.chapterTitle} · $date"
        }
    }

    private fun selectedTextForDisplay(snippet: String): String {
        val marker = "__LIVRE_SELECTED_V1__"
        if (!snippet.startsWith(marker)) return snippet
        return runCatching {
            org.json.JSONObject(snippet.removePrefix(marker)).optString("text").ifBlank { snippet }
        }.getOrDefault(snippet)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BookmarkEntity>() {
            override fun areItemsTheSame(o: BookmarkEntity, n: BookmarkEntity) = o.id == n.id
            override fun areContentsTheSame(o: BookmarkEntity, n: BookmarkEntity) = o == n
        }
    }
}
