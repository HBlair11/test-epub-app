package com.epubreader.app.ui

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * A plain [WebView] subclass retained for future use.
 *
 * Patch v37 follow-up: the `startActionMode` override and `ActionMode.Callback2`
 * wrapper that lived here were removed because Google Play Protect flagged them
 * as a security-protection bypass (intercepting system selection callbacks).
 * Selection-menu customization now happens in [com.epubreader.app.ReaderActivity.onActionModeStarted]
 * via a `Handler.post` re-add approach that does not override any system
 * callbacks.
 *
 * The class itself is kept (rather than deleted) so the layout XML does not need
 * to change its fully-qualified view name, and so future non-security-sensitive
 * overrides have a natural home.
 */
class LivreWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle,
) : WebView(context, attrs, defStyleAttr)
