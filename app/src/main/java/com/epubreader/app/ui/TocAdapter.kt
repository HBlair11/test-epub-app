package com.epubreader.app.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.R
import com.epubreader.app.databinding.ItemTocBinding
import com.epubreader.app.epub.TocEntry

class TocAdapter(
    private val onClick: (TocEntry) -> Unit
) : ListAdapter<TocEntry, TocAdapter.VH>(DIFF) {

    private var selectedPosition =
        RecyclerView.NO_POSITION

    fun setSelectedPosition(
        position: Int
    ) {

        if (position == selectedPosition) {
            return
        }

        val oldPosition =
            selectedPosition

        selectedPosition =
            position

        if (
            oldPosition != RecyclerView.NO_POSITION &&
            oldPosition < itemCount
        ) {
            notifyItemChanged(oldPosition)
        }

        if (
            selectedPosition != RecyclerView.NO_POSITION &&
            selectedPosition < itemCount
        ) {
            notifyItemChanged(selectedPosition)
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): VH {

        val binding =
            ItemTocBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

        return VH(binding)
    }

    override fun onBindViewHolder(
        holder: VH,
        position: Int
    ) {

        val entry =
            getItem(position)

        holder.bind(
            entry = entry,
            selected = position == selectedPosition
        )

        holder.itemView.setOnClickListener {
            val adapterPosition =
                holder.bindingAdapterPosition

            if (
                adapterPosition != RecyclerView.NO_POSITION
            ) {
                onClick(
                    getItem(adapterPosition)
                )
            }
        }
    }

    inner class VH(
        private val binding: ItemTocBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            entry: TocEntry,
            selected: Boolean
        ) {

            binding.title.text =
                entry.label

            val indent =
                (
                        entry.level
                            .coerceAtLeast(0) * 20
                        ).coerceAtMost(80)

            binding.indent.layoutParams =
                (
                        binding.indent.layoutParams
                                as ViewGroup.MarginLayoutParams
                        ).apply {
                        width = indent
                    }

            binding.root.isActivated =
                selected

            binding.title.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (selected) R.color.reader_chrome_accent else R.color.reader_chrome_text
                )
            )

            binding.title.setTypeface(
                null,
                if (selected) {
                    Typeface.BOLD
                } else {
                    Typeface.NORMAL
                }
            )
        }
    }

    companion object {

        private val DIFF =
            object : DiffUtil.ItemCallback<TocEntry>() {

                override fun areItemsTheSame(
                    oldItem: TocEntry,
                    newItem: TocEntry
                ): Boolean =
                    oldItem.href == newItem.href &&
                            oldItem.label == newItem.label

                override fun areContentsTheSame(
                    oldItem: TocEntry,
                    newItem: TocEntry
                ): Boolean =
                    oldItem == newItem
            }
    }
}
