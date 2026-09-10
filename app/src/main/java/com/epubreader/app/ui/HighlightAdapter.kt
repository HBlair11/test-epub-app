package com.epubreader.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.data.HighlightEntity
import com.epubreader.app.databinding.ItemHighlightBinding

class HighlightAdapter(
    private val onDelete: (HighlightEntity) -> Unit,
    private val onClick: (HighlightEntity) -> Unit,
) : ListAdapter<HighlightEntity, HighlightAdapter.VH>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemHighlightBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val b: ItemHighlightBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: HighlightEntity) {
            b.title.text = item.text
            b.subtitle.text = item.spineHref.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
            b.note.visibility = if (item.note.isNullOrBlank()) View.GONE else View.VISIBLE
            b.note.text = item.note.orEmpty()
            b.colorSwatch.setBackgroundColor(item.color)
            b.root.setOnClickListener { onClick(item) }
            b.btnDelete.setOnClickListener { onDelete(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HighlightEntity>() {
            override fun areItemsTheSame(oldItem: HighlightEntity, newItem: HighlightEntity) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: HighlightEntity, newItem: HighlightEntity) = oldItem == newItem
        }
    }
}
