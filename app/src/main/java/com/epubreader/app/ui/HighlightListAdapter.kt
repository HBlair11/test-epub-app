package com.epubreader.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.data.HighlightEntity

/** Simple adapter for displaying highlights in the TOC/Bookmarks overlay. */
class HighlightListAdapter(
    private val onClick: (HighlightEntity) -> Unit,
) : ListAdapter<HighlightEntity, HighlightListAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, (12 * density).toInt(), pad, (12 * density).toInt())
            isClickable = true
        }
        val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        row.background = ta.getDrawable(0)
        ta.recycle()

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
        row.addView(colorDot)
        row.addView(text)
        row.addView(note)
        return VH(row, colorDot, text, note)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.bind(item, onClick)
    }

    class VH(
        itemView: View,
        private val colorDot: View,
        private val text: TextView,
        private val note: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(item: HighlightEntity, onClick: (HighlightEntity) -> Unit) {
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
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<HighlightEntity>() {
            override fun areItemsTheSame(o: HighlightEntity, n: HighlightEntity) = o.id == n.id
            override fun areContentsTheSame(o: HighlightEntity, n: HighlightEntity) = o == n
        }
    }
}
