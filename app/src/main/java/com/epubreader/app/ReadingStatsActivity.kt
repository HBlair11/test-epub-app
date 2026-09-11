package com.epubreader.app

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.epubreader.app.data.AppDatabase
import com.epubreader.app.util.SystemBarController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormatSymbols
import java.util.Calendar

class ReadingStatsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reading_stats)
        SystemBarController.apply(this)
        val root = findViewById<android.view.View>(R.id.statsRootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        val content = findViewById<LinearLayout>(R.id.statsRoot)
        findViewById<android.widget.ImageButton>(R.id.statsBack).setOnClickListener { finish() }
        val now = System.currentTimeMillis()
        val weekStart = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }.timeInMillis
        val yearStart = Calendar.getInstance().apply {
            set(Calendar.MONTH, Calendar.JANUARY); set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                val db = AppDatabase.get(applicationContext)
                val dao = db.readingSessionDao()
                StatsValues(
                    weekSeconds = dao.activeSecondsSince(weekStart),
                    yearSeconds = dao.activeSecondsSinceYear(yearStart),
                    chapters = dao.chaptersSince(yearStart),
                    pages = dao.pagesSince(yearStart),
                    finished = db.bookDao().getFinishedCount(),
                    streak = currentStreak(dao.activeDays(), now),
                )
            }
            findViewById<TextView>(R.id.statsWeek).text = formatMinutes(stats.weekSeconds)
            findViewById<TextView>(R.id.statsYear).text = formatMinutes(stats.yearSeconds)
            findViewById<TextView>(R.id.statsFinished).text = stats.finished.toString()
            findViewById<TextView>(R.id.statsStreak).text = "${stats.streak} ${getString(R.string.stats_days)}"
            findViewById<TextView>(R.id.statsChapters).text = stats.chapters.toString()
            findViewById<TextView>(R.id.statsPages).text = stats.pages.toString()
        }
    }

    private fun formatMinutes(seconds: Int): String {
        val minutes = seconds / 60
        return when {
            minutes < 60 -> getString(R.string.stats_minutes_value, minutes)
            else -> getString(R.string.stats_hours_value, minutes / 60, minutes % 60)
        }
    }

    private fun currentStreak(days: List<String>, now: Long): Int {
        if (days.isEmpty()) return 0
        val today = Calendar.getInstance()
        val keys = days.toHashSet()
        var streak = 0
        val cursor = Calendar.getInstance().apply {
            timeInMillis = today.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (true) {
            val key = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cursor.time)
            if (!keys.contains(key)) break
            streak++
            cursor.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    private data class StatsValues(
        val weekSeconds: Int, val yearSeconds: Int, val chapters: Int, val pages: Int, val finished: Int, val streak: Int
    )
}
