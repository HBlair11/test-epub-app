package com.epubreader.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.R
import com.epubreader.app.databinding.ItemMetadataRefreshBinding
import com.epubreader.app.databinding.ItemMetadataRefreshHeaderBinding
import com.epubreader.app.epub.EpubImporter

class MetadataRefreshAdapter(
    private val context: Context,
    private val onBookClick: (Long) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {

        data class Header(
            val title: String,
        ) : Row()

        data class Book(
            val item: EpubImporter.MetadataRefreshItem,
        ) : Row()
    }

    private val rows =
        mutableListOf<Row>()

    fun submitReport(
        report: EpubImporter.MetadataRefreshResult
    ) {

        rows.clear()

        addSection(
            context.getString(R.string.metadata_refresh_updated),
            report.items.filter {
                it.status ==
                        EpubImporter.MetadataRefreshStatus.UPDATED
            }
        )

        addSection(
            context.getString(R.string.metadata_refresh_unchanged),
            report.items.filter {
                it.status ==
                        EpubImporter.MetadataRefreshStatus.UNCHANGED
            }
        )

        addSection(
            context.getString(R.string.metadata_refresh_skipped),
            report.items.filter {
                it.status ==
                        EpubImporter.MetadataRefreshStatus.SKIPPED
            }
        )

        addSection(
            context.getString(R.string.metadata_refresh_failed),
            report.items.filter {
                it.status ==
                        EpubImporter.MetadataRefreshStatus.FAILED
            }
        )

        notifyDataSetChanged()
    }

    private fun addSection(
        title: String,
        items: List<EpubImporter.MetadataRefreshItem>,
    ) {
        if (items.isEmpty()) return

        rows += Row.Header(
            "$title (${items.size})"
        )

        items.forEach {
            rows += Row.Book(it)
        }
    }

    override fun getItemViewType(position: Int): Int =
        when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Book -> TYPE_BOOK
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {

        return if (viewType == TYPE_HEADER) {

            HeaderVH(
                ItemMetadataRefreshHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        } else {

            BookVH(
                ItemMetadataRefreshBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {

        when (val row = rows[position]) {

            is Row.Header -> {
                (holder as HeaderVH).bind(row)
            }

            is Row.Book -> {
                (holder as BookVH).bind(row.item)
            }
        }
    }

    override fun getItemCount(): Int =
        rows.size

    private inner class HeaderVH(
        private val binding:
        ItemMetadataRefreshHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row.Header) {
            binding.title.text = row.title
        }
    }

    private inner class BookVH(
        private val binding:
        ItemMetadataRefreshBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: EpubImporter.MetadataRefreshItem
        ) {

            binding.title.text = item.displayName

            binding.author.text = item.author

            binding.author.visibility =
                if (item.author.isBlank()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

            binding.status.text =
                when (item.status) {

                    EpubImporter.MetadataRefreshStatus.UPDATED ->
                        context.getString(R.string.metadata_refresh_updated)

                    EpubImporter.MetadataRefreshStatus.UNCHANGED ->
                        context.getString(R.string.metadata_refresh_unchanged)

                    EpubImporter.MetadataRefreshStatus.SKIPPED ->
                        context.getString(R.string.metadata_refresh_skipped)

                    EpubImporter.MetadataRefreshStatus.FAILED ->
                        context.getString(R.string.metadata_refresh_failed)
                }

            binding.detail.text = item.detail

            binding.root.setOnClickListener {

                item.bookId?.let { id ->
                    onBookClick(id)
                }
            }

            binding.root.isClickable =
                item.bookId != null
        }
    }

    companion object {
        private const val TYPE_HEADER = 1
        private const val TYPE_BOOK = 2
    }
}
