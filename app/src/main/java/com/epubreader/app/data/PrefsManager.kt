package com.epubreader.app.data

import android.content.Context
import android.content.SharedPreferences
import com.epubreader.app.ui.ReaderTheme

/**
 * Persists reader appearance + bookshelf view/sort preferences.
 */
class PrefsManager(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("epub_prefs", Context.MODE_PRIVATE)

    // ---- Bookshelf ----
    var viewModeGrid: Boolean
        get() = prefs.getBoolean(KEY_GRID, true)
        set(value) {
            prefs.edit().putBoolean(KEY_GRID, value).apply()
        }

    var sortOption: String
        get() = prefs.getString(KEY_SORT, SortOption.RECENTLY_ADDED) ?: SortOption.RECENTLY_ADDED
        set(value) {
            prefs.edit().putString(KEY_SORT, value).apply()
        }

    var sortAscending: Boolean
        get() = prefs.getBoolean(KEY_SORT_DIR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SORT_DIR, value).apply()
        }

    var gridColumns: Int
        get() = prefs.getInt(KEY_GRID_COLS, 3)
        set(value) {
            prefs.edit().putInt(KEY_GRID_COLS, value.coerceIn(2, 4)).apply()
        }

    var activeTab: Int
        get() = prefs.getInt(KEY_TAB, 0)
        set(value) {
            prefs.edit().putInt(KEY_TAB, value).apply()
        }

    var searchQuery: String
        get() = prefs.getString(KEY_SEARCH, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SEARCH, value).apply()
        }

    var lastView: String
        get() = prefs.getString(KEY_LAST_VIEW, "library") ?: "library"
        set(value) {
            prefs.edit().putString(KEY_LAST_VIEW, value).apply()
        }

    // ---- Reader appearance ----
    // Patch 17 (Addition #1): theme ids are the [ReaderTheme.id] values from the
    // single-source-of-truth registry (ReaderThemes.kt). The getter migrates the
    // 3 pre-Patch-17 ids (light/sepia/dark) to their renamed equivalents
    // (ivory/alabaster/onyx) once, then stores the new id so future reads are
    // a direct hit.
    var theme: String
        get() {
            val raw = prefs.getString(KEY_THEME, Theme.IVORY) ?: Theme.IVORY
            val migrated = ReaderTheme.migrate(raw)
            if (migrated != null && migrated != raw) {
                prefs.edit().putString(KEY_THEME, migrated).apply()
                return migrated
            }
            return raw
        }
        set(value) {
            prefs.edit().putString(KEY_THEME, value).apply()
        }

    var font: String
        get() = prefs.getString(KEY_FONT, Font.SERIF) ?: Font.SERIF
        set(value) {
            prefs.edit().putString(KEY_FONT, value).apply()
        }

    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        set(value) {
            prefs.edit().putInt(KEY_FONT_SIZE, value.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)).apply()
        }

    var lineHeight: Float
        get() = prefs.getFloat(KEY_LINE_HEIGHT, DEFAULT_LINE_HEIGHT).coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)
        set(value) {
            prefs.edit().putFloat(KEY_LINE_HEIGHT, value.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)).apply()
        }

    // Patch 16 (Addition #2): default 20, range 20..72, step 2 for the side
    // margins. coerceIn guards any pre-Patch-16 install that persisted a
    // margin below 20 (old default/range was 0..48) so the Material Slider
    // never receives an initial value outside its valueFrom..valueTo, which
    // would throw.
    var margin: Int
        get() = prefs.getInt(KEY_MARGIN, DEFAULT_MARGIN).coerceIn(MIN_MARGIN, MAX_MARGIN)
        set(value) {
            prefs.edit().putInt(KEY_MARGIN, value.coerceIn(MIN_MARGIN, MAX_MARGIN)).apply()
        }

    var justify: Boolean
        get() = prefs.getBoolean(KEY_JUSTIFY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_JUSTIFY, value).apply()
        }

    /** Text alignment for the reader. ORIGINAL = honor the book's own alignment. */
    var align: String
        get() = prefs.getString(KEY_ALIGN, null)
            ?: if (prefs.getBoolean(KEY_JUSTIFY, false)) Align.JUSTIFY else Align.LEFT
        set(value) {
            prefs.edit().putString(KEY_ALIGN, value).apply()
        }

    /** Soft hyphenation for justified/ragged text. Off by default. */
    var hyphenation: Boolean
        get() = prefs.getBoolean(KEY_HYPHENS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HYPHENS, value).apply()
        }

    /** When true, a bottom safe-area guard is reserved so page content never sits
     *  flush against the phone nav bar (room for the persistent page indicator). */
    var pageBottomMargin: Boolean
        get() = prefs.getBoolean(KEY_BOTTOM_GUARD, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BOTTOM_GUARD, value).apply()
        }

    /** Persisted SAF tree URI of the user's selected library folder. */
    var selectedFolderUri: String?
        get() = prefs.getString(KEY_FOLDER, null)
        set(value) {
            prefs.edit().putString(KEY_FOLDER, value).apply()
        }

    /** Whether the Screen On toggle (keep screen awake beyond system timeout)
     *  is enabled in Settings. Off by default. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) {
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
        }

    /** Patch 17 (Addition #2): when true, in-chapter / cross-chapter page turns
     *  play a short slide animation that reads like turning a physical page;
     *  when false, pages change instantly with no animation. Default ON so the
     *  update preserves the pre-Patch-17 animated experience. */
    var pageTurnAnimation: Boolean
        get() = prefs.getBoolean(KEY_PAGE_TURN_ANIM, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PAGE_TURN_ANIM, value).apply()
        }

    /** TTS speech rate, stored as an int 0-19 representing 0.5x to 1.5x. */
    var ttsSpeedProgress: Int
        get() = prefs.getInt(KEY_TTS_SPEED, 8)
        set(value) {
            prefs.edit().putInt(KEY_TTS_SPEED, value.coerceIn(0, 19)).apply()
        }

    companion object {
        const val KEY_GRID = "view_grid"
        const val KEY_GRID_COLS = "grid_columns"
        const val KEY_SORT = "sort"
        const val KEY_SORT_DIR = "sort_dir"
        const val KEY_TAB = "tab"
        const val KEY_SEARCH = "search_query"
        const val KEY_LAST_VIEW = "last_view"
        const val KEY_THEME = "theme"
        const val KEY_FONT = "font"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_LINE_HEIGHT = "line_height"
        const val KEY_MARGIN = "margin"
        const val KEY_JUSTIFY = "justify"
        const val KEY_ALIGN = "align"
        const val KEY_HYPHENS = "hyphenation"
        const val KEY_BOTTOM_GUARD = "bottom_guard"
        const val KEY_FOLDER = "selected_folder_uri"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_PAGE_TURN_ANIM = "page_turn_animation"
        const val KEY_TTS_SPEED = "tts_speed"

        const val MIN_FONT_SIZE = 24
        const val MAX_FONT_SIZE = 56
        const val DEFAULT_FONT_SIZE = MIN_FONT_SIZE

        // Patch 16 (Addition #3): line-height slider max raised from 2.2 to 2.6;
        // the default and lowest stay unchanged (1.6 and 1.0).
        const val MIN_LINE_HEIGHT = 1.0f
        const val MAX_LINE_HEIGHT = 2.6f
        const val DEFAULT_LINE_HEIGHT = 1.6f

        // Patch 16 (Addition #2): side-margin slider range 20..72, step 2,
        // default 20.
        const val MIN_MARGIN = 20
        const val MAX_MARGIN = 72
        const val DEFAULT_MARGIN = 20
        const val STEP_MARGIN = 2
    }

    object SortOption {
        const val RECENTLY_READ = "recently_read"
        const val RECENTLY_ADDED = "recently_added"
        const val TITLE = "title"
        const val AUTHOR = "author"
        const val SERIES = "series"
        const val PROGRESS = "progress"
    }

    object Theme {
        // Patch 17 (Addition #1): renamed themes. These ids are the persisted
        // values; the display names + colors live in ReaderThemes.kt (single
        // source of truth). Legacy ids (light/sepia/dark) are migrated on read
        // — see PrefsManager.theme getter.
        const val IVORY = "ivory"
        const val NORDIC_ECO = "nordic_eco"
        const val ALABASTER = "alabaster"
        const val CANDLELIGHT = "candlelight"
        const val ONYX = "onyx"
        const val MIDNIGHT_SLATE = "midnight_slate"
    }

    object Font {
        const val SERIF = "serif"
        const val SANS = "sans"
        const val MONO = "mono"
        const val BOOK = "book"
        const val HUMANIST = "humanist"

        /** Use the book's own embedded fonts (@font-face) — no font-family override. */
        const val PUBLISHER = "publisher"
    }

    object Align {
        const val LEFT = "left"
        const val JUSTIFY = "justify"
        const val CENTER = "center"
        const val RIGHT = "right"
        const val ORIGINAL = "original"
    }
}
