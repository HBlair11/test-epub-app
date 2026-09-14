package com.epubreader.app.util

import android.app.Activity
import com.epubreader.app.data.PrefsManager

/** Applies the optional web-app visual theme before an Activity creates its views. */
object AppUiTheme {
    enum class Screen { MAIN, READER, READER_SETTINGS }

    fun apply(activity: Activity, screen: Screen) {
        val enabled = PrefsManager(activity.applicationContext).webAppThemeEnabled
        val themeRes = when (screen) {
            Screen.MAIN -> if (enabled) com.epubreader.app.R.style.Theme_EpubReader_Web else com.epubreader.app.R.style.Theme_EpubReader
            Screen.READER -> if (enabled) com.epubreader.app.R.style.Theme_EpubReader_Reader_Web else com.epubreader.app.R.style.Theme_EpubReader_Reader
            Screen.READER_SETTINGS -> if (enabled) com.epubreader.app.R.style.Theme_EpubReader_Settings_Web else com.epubreader.app.R.style.Theme_EpubReader_Settings
        }
        activity.setTheme(themeRes)
    }
}
