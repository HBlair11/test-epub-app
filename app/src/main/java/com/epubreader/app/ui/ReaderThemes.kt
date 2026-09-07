package com.epubreader.app.ui

import android.graphics.Color

/**
 * ReaderThemes — SINGLE SOURCE OF TRUTH for every EPUB reading theme.
 *
 * Patch 17 (Addition #1): all reader theme colors + display order + the
 * "force ink through descendants" flag live HERE. To add, rename, recolor,
 * or reorder a theme, edit only [ALL] below — the settings dropdown,
 * ReaderActivity (CSS + window), and MainActivity label all read from this
 * registry, so a single edit propagates everywhere.
 *
 * Each [ReaderTheme]:
 *  - [id]            — the string persisted to SharedPreferences (PrefsManager.Theme).
 *                     NEVER change an id once shipped; rename the display name instead.
 *  - [displayNameRes] — localized name shown in the dropdown + main-app label.
 *  - [bgHex]          — content-area background (the "page").
 *  - [inkHex]         — body text / link color on that page.
 *  - [needsInkOverride] — when true, ReaderActivity forces the ink color through
 *                         every descendant element AND flattens hard-coded
 *                         background-colors to transparent (the standard reader
 *                         tradeoff for tinted/dark pages: a hard-coded white box
 *                         on a cream/slate page would otherwise show as a bar).
 *                         False only for a pure-white page (Ivory) where it is
 *                         unnecessary.
 *
 * Display order in the settings dropdown = the order of [ALL].
 */
data class ReaderTheme(
    val id: String,
    val displayNameRes: Int,
    val bgHex: String,
    val inkHex: String,
    val needsInkOverride: Boolean,
) {
    val bgColor: Int get() = Color.parseColor(bgHex)
    val inkColor: Int get() = Color.parseColor(inkHex)

    companion object {
        // ---- The 6 shipped reader themes (Patch 17) ----
        // Order here == order in the Theme dropdown.
        val IVORY = ReaderTheme(
            id = "ivory",
            displayNameRes = com.epubreader.app.R.string.theme_ivory,
            bgHex = "#FFFFFF",
            inkHex = "#000000",
            needsInkOverride = false,
        )
        val NORDIC_ECO = ReaderTheme(
            id = "nordic_eco",
            displayNameRes = com.epubreader.app.R.string.theme_nordic_eco,
            bgHex = "#E8EFE9",
            inkHex = "#242B27",
            needsInkOverride = true,
        )
        val ALABASTER = ReaderTheme(
            id = "alabaster",
            displayNameRes = com.epubreader.app.R.string.theme_alabaster,
            bgHex = "#F1E3D3",
            inkHex = "#5A4650",
            needsInkOverride = true,
        )
        val CANDLELIGHT = ReaderTheme(
            id = "candlelight",
            displayNameRes = com.epubreader.app.R.string.theme_candlelight,
            bgHex = "#E8D3A7",
            inkHex = "#2B1A0A",
            needsInkOverride = true,
        )
        val ONYX = ReaderTheme(
            id = "onyx",
            displayNameRes = com.epubreader.app.R.string.theme_onyx,
            bgHex = "#000000",
            inkHex = "#FFFFFF",
            needsInkOverride = true,
        )
        val MIDNIGHT_SLATE = ReaderTheme(
            id = "midnight_slate",
            displayNameRes = com.epubreader.app.R.string.theme_midnight_slate,
            bgHex = "#1A1B1E",
            inkHex = "#D1D5DB",
            needsInkOverride = true,
        )

        /** Ordered list shown in the Theme dropdown. Edit order here. */
        val ALL: List<ReaderTheme> = listOf(
            IVORY,
            NORDIC_ECO,
            ALABASTER,
            CANDLELIGHT,
            ONYX,
            MIDNIGHT_SLATE,
        )

        /** Look up a theme by its persisted id. Falls back to [IVORY] for any
         *  unknown id (including pre-Patch-17 ids migrated away — see
         *  PrefsManager.Theme.migrate). */
        fun byId(id: String?): ReaderTheme = ALL.firstOrNull { it.id == id } ?: IVORY

        // ---- Legacy id migration (Patch 17 renamed the 3 original themes) ----
        // Old persisted ids are mapped to their renamed equivalents so existing
        // installs keep the same colors after the update. Returns the NEW id.
        fun migrate(oldId: String?): String? = when (oldId) {
            "light" -> IVORY.id
            "sepia" -> ALABASTER.id
            "dark" -> ONYX.id
            else -> null   // null == not a legacy id (already a new id, or null)
        }
    }
}
