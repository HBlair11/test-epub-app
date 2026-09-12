package com.epubreader.app.ui

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * WebView subclass retained as the reader's custom view type.
 *
 * ReaderActivity owns the reader selection toolbar while this WebView continues
 * to provide the real Android/Chromium text selection and selection handles.
 */
class LivreWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle,
) : WebView(context, attrs, defStyleAttr)
