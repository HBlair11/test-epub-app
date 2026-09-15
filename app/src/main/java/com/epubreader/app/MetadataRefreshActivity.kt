package com.epubreader.app

import com.epubreader.app.util.SystemBarController

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.epubreader.app.databinding.ActivityMetadataRefreshBinding
import com.epubreader.app.epub.MetadataRefreshReportStore
import com.epubreader.app.ui.MetadataRefreshAdapter

class MetadataRefreshActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMetadataRefreshBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding =
            ActivityMetadataRefreshBinding.inflate(layoutInflater)

        setContentView(binding.root)
        SystemBarController.apply(this)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)

        setSupportActionBar(binding.toolbar)

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        val report =
            MetadataRefreshReportStore.latest

        if (report == null) {
            finish()
            return
        }

        binding.summary.text = getString(
            com.epubreader.app.R.string.metadata_refresh_report_summary,
            report.items.size,
        )
        binding.counts.text = getString(
            com.epubreader.app.R.string.metadata_refresh_report_counts,
            report.updated, report.unchanged, report.skipped, report.failed,
        )

        binding.recycler.layoutManager =
            LinearLayoutManager(this)

        binding.recycler.adapter =
            MetadataRefreshAdapter(
                context = this,
                onBookClick = { bookId ->
                    startActivity(
                        android.content.Intent(
                            this,
                            BookDetailsActivity::class.java
                        ).putExtra(
                            BookDetailsActivity.EXTRA_BOOK_ID,
                            bookId
                        )
                    )
                }
            ).also {
                it.submitReport(report)
            }
    }
}
