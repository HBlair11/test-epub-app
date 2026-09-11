package com.epubreader.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.R
import com.epubreader.app.data.HighlightEntity

/**
 * Adapter for displaying highlights in the TOC/Bookmarks/Highlights overlay.
 *
 * Patch v37: each row uses an XML layout (item_highlight.xml) so the text
 * column is measured correctly (width=0dp + weight=1 gives the TextView a
 * real width to wrap against, fixing the vertical-text bug). Each row can
 * optionally show a delete button (used by the reader's Highlights tab) via
 * [onDelete]; tapping the row content still jumps to the highlight.
 */
class HighlightListAdapter(
    private val onClick: (HighlightEntity) -> Unit,
    private val onDelete: ((HighlightEntity) -> Unit)? = null,
) : ListAdapter<HighlightEntity, HighlightListAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_highlight, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), onClick, onDelete)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorDot: View = itemView.findViewById(R.id.colorDot)
        private val text: TextView = itemView.findViewById(R.id.highlightText)
        private val note: TextView = itemView.findViewById(R.id.highlightNote)
        private val delete: ImageButton = itemView.findViewById(R.id.btnDelete)

        fun bind(
            item: HighlightEntity,
            onClick: (HighlightEntity) -> Unit,
            onDelete: ((HighlightEntity) -> Unit)?,
        ) {
            val ctx = itemView.context
            val r = android.graphics.Color.red(item.color)
            val g = android.graphics.Color.green(item.color)
            val b = android.graphics.Color.blue(item.color)
            colorDot.setBackgroundColor(android.graphics.Color.rgb(r, g, b))
            text.text = item.text
            if (!item.note.isNullOrBlank()) {
                note.visibility = View.VISIBLE
                note.text = item.note
            } else {
                note.visibility = View.GONE
            }
            delete.visibility = if (onDelete != null) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onClick(item) }
            delete.setOnClickListener {
                delete.isClickable = false
                onDelete?.invoke(item)
                delete.isClickable = true
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<HighlightEntity>() {
            override fun areItemsTheSame(o: HighlightEntity, n: HighlightEntity) = o.id == n.id
            override fun areContentsTheSame(o: HighlightEntity, n: HighlightEntity) = o == n
        }
    }
}
