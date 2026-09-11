package com.epubreader.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView

/**
 * A [WebView] that lets the host customize the floating text-selection toolbar
 * (the Copy / Translate / Select all / Share / Web search row).
 *
 * Patch v37: the standard Android API for customizing a selection ActionMode —
 * [android.widget.TextView.setCustomSelectionActionModeCallback] — does **not**
 * exist on [WebView]. A WebView builds its selection toolbar from a private
 * Chromium callback ([org.chromium.android_webview.AwActionModeCallback]) that
 * clears and repopulates the menu in `onPrepareActionMode`, so items added from
 * the Activity-level [android.app.Activity.onActionModeStarted] hook get wiped
 * before the toolbar renders. The only reliable interception point is
 * [startActionMode] itself, which the WebView calls (via the View framework)
 * when it begins a text selection. Overriding it here lets us wrap the
 * WebView's own callback with one that (re)adds the host's items in
 * `onPrepareActionMode` — the last point before the toolbar renders — so they
 * survive the WebView's menu rebuild, while every default action stays because
 * the wrapper delegates the full lifecycle to the original callback.
 *
 * The host assigns [selectionMenuDecorator] (an idempotent "add my items to this
 * menu" function). No host wiring = standard WebView behavior, unchanged.
 */
class LivreWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle,
) : WebView(context, attrs, defStyleAttr) {

    /** Idempotently adds the host's selection items (Define, Highlight) to the
     *  given menu. Called from both `onCreateActionMode` and
     *  `onPrepareActionMode` of the wrapped callback. Null = no customization. */
    var selectionMenuDecorator: ((Menu) -> Unit)? = null

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        val decorator = selectionMenuDecorator ?: return super.startActionMode(callback, type)
        val wrapped = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                // Let the WebView populate its default items first, then add ours.
                val res = callback.onCreateActionMode(mode, menu)
                decorator.invoke(menu)
                return res
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                // The WebView's onPrepareActionMode clears + repopulates the
                // menu with its own items; run it first, then re-add ours so
                // they sit on top of the freshly rebuilt menu.
                val res = callback.onPrepareActionMode(mode, menu)
                decorator.invoke(menu)
                return res
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
                callback.onActionItemClicked(mode, item)

            override fun onDestroyActionMode(mode: ActionMode) =
                callback.onDestroyActionMode(mode)
        }
        return super.startActionMode(wrapped, type)
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        startActionMode(callback, ActionMode.TYPE_PRIMARY)
}
