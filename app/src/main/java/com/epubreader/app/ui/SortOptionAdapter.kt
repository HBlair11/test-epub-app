package com.epubreader.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.databinding.ItemSortOptionBinding

class SortOptionAdapter(
    private val options: List<Pair<String, String>>,
    private val onSelect: (String) -> Unit
) : RecyclerView.Adapter<SortOptionAdapter.VH>() {

    private var selected: String = options.firstOrNull()?.second ?: ""

    fun setSelected(value: String) {
        selected = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSortOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = options.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (label, value) = options[position]
        holder.bind(label, value == selected)
    }

    inner class VH(private val b: ItemSortOptionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(label: String, isSelected: Boolean) {
            b.title.text = label
            b.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            b.root.setOnClickListener {
                selected = options[bindingAdapterPosition].second
                onSelect(selected)
                notifyDataSetChanged()
            }
        }
    }
}
