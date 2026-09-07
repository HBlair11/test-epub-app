package com.epubreader.app.util

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Keeps phone system-bar areas visually black across every app screen.
 *
 * Android 15 enforces edge-to-edge for apps targeting API 35, so simply setting
 * statusBarColor/navigationBarColor is not sufficient there. The black inset
 * views cover the transparent system-bar areas without changing the existing
 * screen layouts or reader content.
 */
object SystemBarController {
    private const val TOP_TAG = "livre_system_bar_top"
    private const val BOTTOM_TAG = "livre_system_bar_bottom"

    fun apply(activity: Activity) {
        val window = activity.window
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = Color.BLACK
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (Build.VERSION.SDK_INT < 35) return
        if (content.findViewWithTag<View>(TOP_TAG) == null) {
            content.addView(View(activity).apply {
                tag = TOP_TAG
                setBackgroundColor(Color.BLACK)
                isClickable = false
                isFocusable = false
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                android.view.Gravity.TOP,
            ))
        }
        if (content.findViewWithTag<View>(BOTTOM_TAG) == null) {
            content.addView(View(activity).apply {
                tag = BOTTOM_TAG
                setBackgroundColor(Color.BLACK)
                isClickable = false
                isFocusable = false
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                android.view.Gravity.BOTTOM,
            ))
        }

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val top = view.findViewWithTag<View>(TOP_TAG)
            val bottom = view.findViewWithTag<View>(BOTTOM_TAG)
            (top.layoutParams as? FrameLayout.LayoutParams)?.also { lp ->
                lp.height = bars.top
                top.layoutParams = lp
            }
            (bottom.layoutParams as? FrameLayout.LayoutParams)?.also { lp ->
                lp.height = bars.bottom
                bottom.layoutParams = lp
            }
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

}
