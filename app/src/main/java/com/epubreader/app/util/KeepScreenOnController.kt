package com.epubreader.app.util

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import com.epubreader.app.data.PrefsManager

/**
 * Patch 11 "Screen On" feature.
 *
 * When the user enables the Screen On setting, the screen is kept awake
 * (via [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]) for 10 minutes LONGER
 * than the device's own screen-off timeout, whenever this app is in the
 * foreground — across every screen (Library, Reader, Book Details), not just
 * the main bookshelf activity.
 *
 * Literal "10 minutes longer than your current system setting": the keep-awake
 * duration is `systemScreenOffTimeoutMs + 10 min` (read from
 * [Settings.System.SCREEN_OFF_TIMEOUT]); it does NOT use a hard 10-minute timer.
 * That means if the device's screen-off timeout is, say, 30 seconds, the screen
 * stays on for 30s + 10min before the system's normal timeout logic resumes.
 *
 * Once the keep-awake window elapses the flag is cleared, so the device's normal
 * screen-off timeout takes over. Any user interaction while the preference is
 * still on re-arms the keep-awake window immediately (see [bump]).
 *
 * Usage: one instance per Activity. Call [onResume] / [onPause] from the
 * activity lifecycle and [bump] from [Activity.onUserInteraction]. Each
 * activity owns and clears its own flag/timer, so navigating between
 * activities (e.g. Library -> Reader) hands off cleanly: the outgoing
 * activity's onPause clears its flag, the incoming activity's onResume sets
 * its own if the preference is on.
 */
class KeepScreenOnController(private val activity: Activity, private val prefs: PrefsManager) {

    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private val releaseRunnable = Runnable {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        active = false
    }

    fun onResume() {
        if (prefs.keepScreenOn) arm() else release()
    }

    fun onPause() {
        release()
    }

    /** Call from [Activity.onUserInteraction]. Extends (or re-arms) the keep-awake
     *  window whenever the user is actively using the screen, even if the
     *  previous window already elapsed — as long as the setting is still on. */
    fun bump() {
        if (prefs.keepScreenOn) arm()
    }

    /** Call after the user flips the Screen On switch in Settings so the
     *  change takes effect immediately without waiting for a lifecycle event. */
    fun refresh() {
        if (prefs.keepScreenOn) arm() else release()
    }

    private fun arm() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        active = true
        handler.removeCallbacks(releaseRunnable)
        handler.postDelayed(releaseRunnable, keepAwakeDurationMillis())
    }

    private fun release() {
        handler.removeCallbacks(releaseRunnable)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        active = false
    }

    /** System screen-off timeout (ms) + 10 minutes, with a sane floor in case the
     *  system setting is missing/0 (defaults to ~30s in that case). */
    private fun keepAwakeDurationMillis(): Long {
        val sysTimeout = try {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 0)
        } catch (_: Throwable) {
            0
        }
        val base = if (sysTimeout > 0) sysTimeout.toLong() else 30_000L
        return base + TEN_MINUTES_MILLIS
    }

    companion object {
        const val TEN_MINUTES_MILLIS = 10L * 60L * 1000L
    }
}
