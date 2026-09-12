package com.epubreader.app.ui

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * WebView subclass retained as the reader's custom view type.
 *
 * Text-selection actions are registered from ReaderActivity through the public
 * View customSelectionActionModeCallback API. This class intentionally does not
 * override startActionMode or intercept private Chromium selection callbacks.
 */
class LivreWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle,
) : WebView(context, attrs, defStyleAttr)
