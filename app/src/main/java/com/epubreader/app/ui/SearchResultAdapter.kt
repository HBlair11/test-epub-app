package com.epubreader.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.databinding.ItemSearchResultBinding
import com.epubreader.app.epub.EpubSearchEngine

class SearchResultAdapter(
    private val onClick: (EpubSearchEngine.Result) -> Unit
) : ListAdapter<EpubSearchEngine.Result, SearchResultAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    inner class VH(private val b: ItemSearchResultBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: EpubSearchEngine.Result) {
            b.snippet.text = "…${item.snippet}…"
            b.chapter.text = item.chapterTitle
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<EpubSearchEngine.Result>() {
            override fun areItemsTheSame(o: EpubSearchEngine.Result, n: EpubSearchEngine.Result) =
                o.chapterIndex == n.chapterIndex && o.snippet == n.snippet

            override fun areContentsTheSame(o: EpubSearchEngine.Result, n: EpubSearchEngine.Result) = o == n
        }
    }
}
