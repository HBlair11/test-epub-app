package com.epubreader.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.R
import com.epubreader.app.data.DictionaryHistoryEntity
import kotlin.math.roundToInt

/**
 * Patch v37: rows for the offline Vocabulary list (dictionary lookup
 * history). Shows the looked-up word, its first saved definition and a
 * delete button; built programmatically to match HighlightListAdapter's
 * row style.
 */
class VocabularyAdapter(
    private val onDelete: (DictionaryHistoryEntity) -> Unit,
) : ListAdapter<DictionaryHistoryEntity, VocabularyAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).roundToInt(), 0, (10 * density).roundToInt())
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val word = TextView(ctx).apply {
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val definition = TextView(ctx).apply {
            textSize = 13f
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (2 * density).roundToInt() }
        }
        content.addView(word)
        content.addView(definition)
        row.addView(content)
        val delete = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_delete)
            background = null
            val pad = (8 * density).roundToInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = ctx.getString(R.string.vocabulary_delete_word)
        }
        row.addView(delete)
        return VH(row, word, definition, delete)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), onDelete)
    }

    class VH(
        itemView: View,
        private val word: TextView,
        private val definition: TextView,
        private val delete: ImageButton,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(item: DictionaryHistoryEntity, onDelete: (DictionaryHistoryEntity) -> Unit) {
            val ctx = itemView.context
            word.text = item.word
            definition.text = listOfNotNull(
                item.partOfSpeech?.takeIf { it.isNotBlank() },
                item.definition,
            ).joinToString("  ").ifBlank { ctx.getString(R.string.dictionary_not_found) }
            val primary = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            word.setTextColor(primary.getColor(0, 0xFF000000.toInt()))
            primary.recycle()
            val secondary = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
            definition.setTextColor(secondary.getColor(0, 0xFF888888.toInt()))
            secondary.recycle()
            delete.setOnClickListener { onDelete(item) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DictionaryHistoryEntity>() {
            override fun areItemsTheSame(o: DictionaryHistoryEntity, n: DictionaryHistoryEntity) = o.id == n.id
            override fun areContentsTheSame(o: DictionaryHistoryEntity, n: DictionaryHistoryEntity) = o == n
        }
    }
}
