package com.epubreader.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.R

data class DrawerItem(
    val label: String,
    val iconRes: Int,
    val count: Int? = null,
    val view: com.epubreader.app.ui.ShelfView? = null,
    val isPlaceholder: Boolean = false,
    val isDivider: Boolean = false,
    val launchActivity: Class<*>? = null,
)

class DrawerAdapter(
    private val onClick: (DrawerItem) -> Unit
) : ListAdapter<DrawerItem, RecyclerView.ViewHolder>(DIFF) {

    private var selectedView: ShelfView? = null

    /** Highlights the drawer row for the given view. Detail views
     *  (author/series) highlight their parent section instead. */
    fun setSelected(view: ShelfView?) {
        selectedView = parentSectionOf(view)
        notifyDataSetChanged()
    }

    private fun parentSectionOf(view: ShelfView?): ShelfView? = when (view) {
        is ShelfView.AuthorDetail -> ShelfView.AuthorsList
        is ShelfView.SeriesDetail -> ShelfView.SeriesList
        else -> view
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.drawerIcon)
        val label: TextView = itemView.findViewById(R.id.drawerLabel)
        val count: TextView = itemView.findViewById(R.id.drawerCount)
    }

    inner class DividerVH(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).isDivider) TYPE_DIVIDER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_DIVIDER) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_drawer_divider, parent, false)
            return DividerVH(v)
        }

        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_drawer, parent, false)

        return VH(v).also { vh ->
            vh.itemView.setOnClickListener {
                val position = vh.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onClick(getItem(position))
                }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is VH) return

        val item = getItem(position)
        holder.label.text = item.label
        holder.icon.setImageResource(item.iconRes)

        val active = item.view != null && item.view == selectedView
        holder.itemView.isSelected = active
        holder.label.setTypeface(
            null,
            if (active) android.graphics.Typeface.BOLD
            else android.graphics.Typeface.NORMAL
        )

        if (item.count != null && item.count > 0) {
            holder.count.visibility = View.VISIBLE
            holder.count.text = item.count.toString()
        } else {
            holder.count.visibility = View.GONE
        }
    }

    companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_DIVIDER = 1

        val DIFF = object : DiffUtil.ItemCallback<DrawerItem>() {
            override fun areItemsTheSame(o: DrawerItem, n: DrawerItem) =
                o.isDivider == n.isDivider && o.label == n.label

            override fun areContentsTheSame(o: DrawerItem, n: DrawerItem) = o == n
        }
    }
}
