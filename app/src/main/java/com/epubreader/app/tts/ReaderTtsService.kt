package com.epubreader.app.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.epubreader.app.R
import com.epubreader.app.ReaderActivity
import com.epubreader.app.epub.ReaderTtsController

/**
 * Patch v37: foreground keep-alive service for Read Aloud.
 *
 * The system TTS engine lives in its own process; this app process only feeds
 * it sentence-by-sentence. Without a foreground service Android can kill the
 * process once the reader is backgrounded / the screen turns off, which cuts
 * the audio mid-sentence. While "Keep reading in background" is enabled and
 * playback is active, this service holds a persistent media notification so
 * the process survives, and offers play/pause + stop from the notification.
 *
 * The service owns no audio logic — it delegates the notification actions to
 * the single active [ReaderTtsController] via [attach]/[detach].
 */
class ReaderTtsService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_RESUME -> controller?.togglePauseResume()
            ACTION_STOP -> {
                controller?.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> Unit
        }
        // Refresh the notification with the extras carried on the action
        // intents so pausing from the shade never loses the book info.
        val bookId = intent?.getLongExtra(EXTRA_BOOK_ID, -1L) ?: -1L
        val title = intent?.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
        val playing = controller?.state == ReaderTtsController.State.PLAYING
        startInForeground(title.ifBlank { getString(R.string.app_name) }, bookId, playing)
        return START_NOT_STICKY
    }

    private fun startInForeground(bookTitle: String, bookId: Long, playing: Boolean) {
        val notification = buildNotification(bookTitle, bookId, playing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(bookTitle: String, bookId: Long, playing: Boolean): Notification {
        val openIntent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = Intent(this, ReaderTtsService::class.java).apply {
            action = ACTION_PAUSE_RESUME
            putExtra(EXTRA_BOOK_ID, bookId)
            putExtra(EXTRA_BOOK_TITLE, bookTitle)
        }
        val pausePi = PendingIntent.getService(
            this, 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, ReaderTtsService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleLabel = getString(if (playing) R.string.tts_pause else R.string.tts_resume)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume)
            .setContentTitle(getString(R.string.tts_notification_title))
            .setContentText(bookTitle)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, toggleLabel, pausePi)
            .addAction(0, getString(R.string.tts_stop), stopPi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 41
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_BOOK_TITLE = "book_title"
        const val ACTION_PAUSE_RESUME = "com.epubreader.app.tts.PAUSE_RESUME"
        const val ACTION_STOP = "com.epubreader.app.tts.STOP"

        /** The single active reader TTS controller (one ReaderActivity at a time). */
        @Volatile
        var controller: ReaderTtsController? = null

        fun attach(controller: ReaderTtsController?) {
            this.controller = controller
        }

        fun start(context: Context, bookId: Long, bookTitle: String) {
            ensureChannel(context)
            val intent = Intent(context, ReaderTtsService::class.java).apply {
                putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_BOOK_TITLE, bookTitle)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReaderTtsService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.tts_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.tts_notification_channel)
                    setShowBadge(false)
                }
            )
        }
    }
}
