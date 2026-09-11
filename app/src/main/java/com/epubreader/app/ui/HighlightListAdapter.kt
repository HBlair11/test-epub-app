package com.epubreader.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.R
import com.epubreader.app.data.HighlightEntity

/**
 * Simple adapter for displaying highlights in the TOC/Bookmarks overlay.
 *
 * Patch v37: each row can optionally show a delete button (used by the
 * reader's Highlights tab) via [onDelete]; tapping the row content still
 * jumps to the highlight.
 */
class HighlightListAdapter(
    private val onClick: (HighlightEntity) -> Unit,
    private val onDelete: ((HighlightEntity) -> Unit)? = null,
) : ListAdapter<HighlightEntity, HighlightListAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, (12 * density).toInt(), pad / 2, (12 * density).toInt())
            isClickable = true
        }
        val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        row.background = ta.getDrawable(0)
        ta.recycle()

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val colorDot = View(ctx).apply {
            val size = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                bottomMargin = (6 * density).toInt()
            }
        }
        val text = TextView(ctx).apply {
            textSize = 14f
            maxLines = 3
        }
        val note = TextView(ctx).apply {
            textSize = 12f
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (4 * density).toInt() }
        }
        content.addView(colorDot)
        content.addView(text)
        content.addView(note)
        row.addView(content)

        val delete = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_delete)
            background = null
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = ctx.getString(R.string.highlight_delete)
            visibility = if (onDelete != null) View.VISIBLE else View.GONE
        }
        row.addView(delete)
        return VH(row, colorDot, text, note, delete)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.bind(item, onClick, onDelete)
    }

    class VH(
        itemView: View,
        private val colorDot: View,
        private val text: TextView,
        private val note: TextView,
        private val delete: ImageButton,
    ) : RecyclerView.ViewHolder(itemView) {
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
            val tv = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            text.setTextColor(tv.getColor(0, 0xFF000000.toInt()))
            tv.recycle()
            val tv2 = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
            note.setTextColor(tv2.getColor(0, 0xFF888888.toInt()))
            tv2.recycle()
            if (!item.note.isNullOrBlank()) {
                note.visibility = View.VISIBLE
                note.text = item.note
            } else {
                note.visibility = View.GONE
            }
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
