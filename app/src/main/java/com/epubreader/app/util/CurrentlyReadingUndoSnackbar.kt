package com.epubreader.app.util

import android.content.Context
import android.os.SystemClock
import android.view.View
import com.epubreader.app.R
import com.epubreader.app.data.BookRepository
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps the Currently Reading removal undo action alive across Activity changes.
 * The pending action is application-scoped, while each Activity only owns the
 * Snackbar view that is currently rendering it.
 */
object CurrentlyReadingUndoSnackbar {
    private const val DURATION_MS = 2750L

    private data class Pending(
        val bookId: Long,
        val expiresAt: Long,
    )

    private var pending: Pending? = null
    private var visible: Snackbar? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun show(context: Context, anchor: View, bookId: Long) {
        pending = Pending(bookId, SystemClock.elapsedRealtime() + DURATION_MS)
        showPending(context, anchor)
    }

    fun showPending(context: Context, anchor: View) {
        val item = pending ?: return
        val remaining = item.expiresAt - SystemClock.elapsedRealtime()
        if (remaining <= 0L) {
            pending = null
            visible = null
            return
        }

        val current = visible
        if (current?.isShown == true) {
            // A Snackbar is attached to the Activity window that created it.
            // If navigation has moved us to another Activity, dismiss the old
            // window-bound Snackbar but keep the application-scoped pending
            // action and its original expiry time. The new Activity can then
            // render the same remaining timer.
            if (current.view.rootView === anchor.rootView) return
            current.dismiss()
            visible = null
        }

        visible = Snackbar.make(anchor, R.string.currently_reading_removed, remaining.toInt())
            .setAction(R.string.undo) {
                val bookId = pending?.bookId ?: return@setAction
                pending = null
                visible = null
                scope.launch(Dispatchers.IO) {
                    BookRepository(context.applicationContext).setCurrentlyReading(bookId)
                }
            }
            .also { snackbar ->
                snackbar.addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        if (visible === transientBottomBar) visible = null
                        if (SystemClock.elapsedRealtime() >= (pending?.expiresAt ?: 0L)) {
                            pending = null
                        }
                    }
                })
                snackbar.show()
            }
    }
}
