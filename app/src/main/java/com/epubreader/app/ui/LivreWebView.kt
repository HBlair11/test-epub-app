package com.epubreader.app.ui

import android.content.Context
import android.graphics.Rect
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
 * Chromium callback that clears and repopulates the menu in
 * `onPrepareActionMode`, so items added from the Activity-level
 * [android.app.Activity.onActionModeStarted] hook get wiped before the toolbar
 * renders. The only reliable interception point is [startActionMode] itself,
 * which the WebView calls (via the View framework) when it begins a text
 * selection. Overriding it here lets us wrap the WebView's own callback with
 * one that (re)adds the host's items in `onPrepareActionMode` — the last point
 * before the toolbar renders — so they survive the WebView's menu rebuild,
 * while every default action stays because the wrapper delegates the full
 * lifecycle to the original callback.
 *
 * The wrapper extends [ActionMode.Callback2] (not the plain Callback) so it can
 * delegate [onGetContentRect] to the original callback when that callback is
 * itself a Callback2. Android uses the content rect to position the floating
 * toolbar near the selected text; if we drop it (by using plain Callback), the
 * toolbar falls back to a fixed position at the top of the screen. Delegating
 * the rect keeps the toolbar floating where the user expects it.
 *
 * The host assigns [selectionMenuDecorator] (an idempotent "add my items to
 * this menu" function). No host wiring = standard WebView behavior, unchanged.
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

    /** Wraps the WebView's own ActionMode.Callback so we can (re)add the host's
     *  selection items after the WebView rebuilds its menu, while delegating
     *  the full lifecycle — including the content rect used to position the
     *  floating toolbar — to the original callback. */
    private fun wrap(callback: ActionMode.Callback): ActionMode.Callback {
        val decorator = selectionMenuDecorator ?: return callback
        val originalIsCallback2 = callback is ActionMode.Callback2
        return object : ActionMode.Callback2() {
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

            // Delegating the content rect is what keeps the floating toolbar
            // positioned near the selected text instead of falling back to the
            // top of the screen.
            override fun onGetContentRect(mode: ActionMode, view: android.view.View?, outRect: Rect) {
                if (originalIsCallback2) {
                    (callback as ActionMode.Callback2).onGetContentRect(mode, view, outRect)
                } else {
                    super.onGetContentRect(mode, view, outRect)
                }
            }
        }
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(wrap(callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)
}
