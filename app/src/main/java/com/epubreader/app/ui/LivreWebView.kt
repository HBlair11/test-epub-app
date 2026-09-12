package com.epubreader.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView

/**
 * WebView subclass retained as the reader's custom view type.
 *
 * ReaderActivity owns the reader selection toolbar while this WebView continues
 * to provide the real Android/Chromium text selection and selection handles.
 *
 * The native floating selection menu is suppressed at the ActionMode callback
 * boundary. This is deliberately synchronous and lifecycle-owned: we do not call
 * ActionMode.hide()/finish() and we do not mutate an ActionMode from a delayed
 * runnable, both of which can destabilize Chromium selection on some devices.
 */
class LivreWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle,
) : WebView(context, attrs, defStyleAttr) {

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? {
        return super.startActionMode(wrapSelectionActionModeCallback(callback))
    }

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        return super.startActionMode(wrapSelectionActionModeCallback(callback), type)
    }

    private fun wrapSelectionActionModeCallback(callback: ActionMode.Callback): ActionMode.Callback {
        return object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val created = callback.onCreateActionMode(mode, menu)
                if (created) menu.clear()
                return created
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                val prepared = callback.onPrepareActionMode(mode, menu)
                menu.clear()
                return prepared
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return callback.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                callback.onDestroyActionMode(mode)
            }
        }
    }
}
