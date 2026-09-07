package com.epubreader.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.data.CollectionEntity
import com.epubreader.app.data.GroupedRow
import com.epubreader.app.databinding.ItemAuthorSeriesBinding

/** Displays a row with an icon, title, subtitle, and a count chip. */
class RowAdapter(
    var iconRes: Int,
    private val onClick: (Pair<String, Long?>) -> Unit,
    private val onLongClick: ((Pair<String, Long?>) -> Boolean)? = null
) : ListAdapter<Any, RowAdapter.RowVH>(DIFF) {

    class RowVd(val name: String, val id: Long?, val count: Int)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
        val b = ItemAuthorSeriesBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RowVH(b)
    }

    override fun onBindViewHolder(holder: RowVH, position: Int) {
        val item = getItem(position)
        val vd = when (item) {
            is GroupedRow -> RowVd(item.name, null, item.count)
            is CollectionEntity -> RowVd(item.name, item.id, -1)
            is RowVd -> item
            else -> return
        }
        holder.bind(vd)
    }

    inner class RowVH(private val b: ItemAuthorSeriesBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(vd: RowVd) {
            b.icon.setImageResource(iconRes)
            b.title.text = vd.name
            b.subtitle.text =
                "${vd.count} " + if (vd.count == 1) itemView.context.getString(com.epubreader.app.R.string.progress_book) else itemView.context.getString(
                    com.epubreader.app.R.string.progress_books
                )
            b.subtitle.visibility = if (vd.count >= 0) android.view.View.VISIBLE else android.view.View.GONE
            b.count.visibility = if (vd.count >= 0) android.view.View.VISIBLE else android.view.View.GONE
            if (vd.count >= 0) b.count.text = vd.count.toString()
            b.root.setOnClickListener { onClick(vd.name to vd.id) }
            onLongClick?.let { b.root.setOnLongClickListener { it(vd.name to vd.id) } }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Any>() {
            override fun areItemsTheSame(o: Any, n: Any): Boolean = when {
                o is GroupedRow && n is GroupedRow -> o.name == n.name
                o is CollectionEntity && n is CollectionEntity -> o.id == n.id
                else -> o === n
            }

            override fun areContentsTheSame(o: Any, n: Any): Boolean = o == n
        }
    }
}
