package com.epubreader.app.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.epubreader.app.R
import com.epubreader.app.data.PrefsManager
import com.epubreader.app.data.PrefsManager.Companion.MAX_FONT_SIZE
import com.epubreader.app.data.PrefsManager.Companion.MAX_LINE_HEIGHT
import com.epubreader.app.data.PrefsManager.Companion.MAX_MARGIN
import com.epubreader.app.data.PrefsManager.Companion.MIN_FONT_SIZE
import com.epubreader.app.data.PrefsManager.Companion.MIN_LINE_HEIGHT
import com.epubreader.app.data.PrefsManager.Companion.MIN_MARGIN
import com.epubreader.app.data.PrefsManager.Companion.STEP_MARGIN
import com.epubreader.app.databinding.ActivityReaderSettingsBinding
import com.epubreader.app.ui.ReaderTheme
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/**
 * Patch 17 (Issue #1): full-screen reader settings ACTIVITY.
 *
 * Replaces the old [ReaderSettingsSheet] BottomSheet. Every value control is a
 * +/- stepper (no sliders — a vertical scroll can never accidentally nudge a
 * value) and every multi-option control is an ExposedDropdownMenu (scales
 * cleanly as more options are added, instead of crowding a toggle row).
 *
 * Settings are NOT applied live: changes are written to [PrefsManager] as the
 * user makes them, and a single `RESULT_OK` is returned when the screen closes
 * (back arrow OR hardware back). ReaderActivity applies them once on return via
 * [applySettingsAndReload]. This avoids re-measuring page counts on every +/- tap.
 */
class ReaderSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderSettingsBinding

    private val prefs by lazy { PrefsManager(applicationContext) }

    /** True once the user changes any setting; becomes the result code. */
    private var dirty = false

    /** Font options in dropdown order (id -> display name). Stable mapping: the
     *  dropdown position selects the entry, never the display string, so
     *  renames/localization can never corrupt the persisted preference. */
    private val fontOptions = listOf(
        PrefsManager.Font.SERIF to R.string.font_serif,
        PrefsManager.Font.SANS to R.string.font_sans,
        PrefsManager.Font.MONO to R.string.font_mono,
        PrefsManager.Font.BOOK to R.string.font_book,
        PrefsManager.Font.HUMANIST to R.string.font_humanist,
        PrefsManager.Font.PUBLISHER to R.string.font_publisher,
    )

    private val alignOptions = listOf(
        PrefsManager.Align.LEFT to R.string.align_left,
        PrefsManager.Align.JUSTIFY to R.string.align_justify,
        PrefsManager.Align.CENTER to R.string.align_center,
        PrefsManager.Align.RIGHT to R.string.align_right,
        PrefsManager.Align.ORIGINAL to R.string.align_original,
    )

    // Line height is stored as a float (1.0..2.6) but stepped as an integer tick
    // (10..26, step 1) then divided by 10 — integers are exact in IEEE-754 float,
    // so the persisted value always lands on a 0.1 grid with no drift (same trick
    // the old slider used; see PrefsManager.MIN/MAX_LINE_HEIGHT).
    private var lineTick: Int = 16

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Lift content clear of the navigation bar so the last toggle is never
        // flush with the phone nav buttons (mirrors the old sheet's inset logic).
        val baseBottomPad = binding.settingsScroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsScroll) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, baseBottomPad + nav.bottom)
            insets
        }

        setSupportActionBar(binding.settingsToolbar)
        binding.settingsToolbar.setNavigationOnClickListener { finishWithResult() }
        // Hardware back must deliver the result too, not just the toolbar arrow.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithResult()
            }
        })

        setupThemeDropdown()
        setupFontDropdown()
        setupAlignDropdown()
        setupFontSizeStepper()
        setupLineHeightStepper()
        setupMarginStepper()
        setupBottomMarginSwitch()
        setupHyphenationSwitch()
        setupPageTurnAnimSwitch()
    }

    private fun finishWithResult() {
        setResult(if (dirty) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    // ---------------------------------------------------------------- dropdowns
    private fun setupThemeDropdown() {
        val names = ReaderTheme.ALL.map { getString(it.displayNameRes) }
        val tv = binding.themeDropdown
        tv.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names))
        val current = ReaderTheme.ALL.indexOfFirst { it.id == prefs.theme }.coerceAtLeast(0)
        tv.setText(names[current], false)
        tv.setOnItemClickListener { _, _, position, _ ->
            val id = ReaderTheme.ALL[position].id
            if (id != prefs.theme) {
                prefs.theme = id; dirty = true
            }
        }
    }

    private fun setupFontDropdown() {
        bindDropdown(binding.fontDropdown, fontOptions, prefs.font) { id ->
            if (id != prefs.font) {
                prefs.font = id; dirty = true
            }
        }
    }

    private fun setupAlignDropdown() {
        bindDropdown(binding.alignDropdown, alignOptions, prefs.align) { id ->
            if (id != prefs.align) {
                prefs.align = id; dirty = true
            }
        }
    }

    /** Generic dropdown binder for an (id, displayNameRes) option list. */
    private fun bindDropdown(
        tv: MaterialAutoCompleteTextView,
        options: List<Pair<String, Int>>,
        currentId: String,
        onSelected: (String) -> Unit,
    ) {
        val names = options.map { getString(it.second) }
        tv.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names))
        val current = options.indexOfFirst { it.first == currentId }.coerceAtLeast(0)
        tv.setText(names[current], false)
        tv.setOnItemClickListener { _, _, position, _ -> onSelected(options[position].first) }
    }

    // ---------------------------------------------------------------- steppers
    private fun setupFontSizeStepper() {
        var value = prefs.fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        if (value != prefs.fontSize) {
            prefs.fontSize = value; dirty = true
        }
        binding.fontSizeValue.text = value.toString()
        binding.fontSizeMinus.isEnabled = value > MIN_FONT_SIZE
        binding.fontSizePlus.isEnabled = value < MAX_FONT_SIZE
        binding.fontSizeMinus.setOnClickListener {
            if (value > MIN_FONT_SIZE) {
                value -= 1; applyFontSize(value)
            }
        }
        binding.fontSizePlus.setOnClickListener {
            if (value < MAX_FONT_SIZE) {
                value += 1; applyFontSize(value)
            }
        }
    }

    private fun applyFontSize(v: Int) {
        prefs.fontSize = v
        dirty = true
        binding.fontSizeValue.text = v.toString()
        binding.fontSizeMinus.isEnabled = v > MIN_FONT_SIZE
        binding.fontSizePlus.isEnabled = v < MAX_FONT_SIZE
    }

    private fun setupLineHeightStepper() {
        // Patch 16.1 guard carried over: round (don't truncate) so a stored
        // 1.9 that drifted to 1.8999999f isn't silently migrated down to 1.8.
        lineTick = Math.round(prefs.lineHeight * 10f).coerceIn(
            (MIN_LINE_HEIGHT * 10).toInt(), (MAX_LINE_HEIGHT * 10).toInt()
        )
        val normalized = lineTick / 10f
        if (normalized != prefs.lineHeight) {
            prefs.lineHeight = normalized; dirty = true
        }
        binding.lineHeightValue.text = formatLineHeight(lineTick)
        binding.lineHeightMinus.isEnabled = lineTick > (MIN_LINE_HEIGHT * 10).toInt()
        binding.lineHeightPlus.isEnabled = lineTick < (MAX_LINE_HEIGHT * 10).toInt()
        binding.lineHeightMinus.setOnClickListener {
            if (lineTick > (MIN_LINE_HEIGHT * 10).toInt()) {
                lineTick -= 1; applyLineHeight()
            }
        }
        binding.lineHeightPlus.setOnClickListener {
            if (lineTick < (MAX_LINE_HEIGHT * 10).toInt()) {
                lineTick += 1; applyLineHeight()
            }
        }
    }

    private fun applyLineHeight() {
        val v = lineTick / 10f
        prefs.lineHeight = v
        dirty = true
        binding.lineHeightValue.text = formatLineHeight(lineTick)
        binding.lineHeightMinus.isEnabled = lineTick > (MIN_LINE_HEIGHT * 10).toInt()
        binding.lineHeightPlus.isEnabled = lineTick < (MAX_LINE_HEIGHT * 10).toInt()
    }

    private fun formatLineHeight(tick: Int): String {
        // Always one decimal place (1.0, 1.6, 2.6) — no float drift because
        // tick is an integer divided by 10.
        val tenths = tick % 10
        val ones = tick / 10
        return "$ones.$tenths"
    }

    private fun setupMarginStepper() {
        var value = prefs.margin.coerceIn(MIN_MARGIN, MAX_MARGIN)
        if (value != prefs.margin) {
            prefs.margin = value; dirty = true
        }
        binding.marginValue.text = value.toString()
        binding.marginMinus.isEnabled = value > MIN_MARGIN
        binding.marginPlus.isEnabled = value < MAX_MARGIN
        binding.marginMinus.setOnClickListener {
            if (value > MIN_MARGIN) {
                value -= STEP_MARGIN; applyMargin(value)
            }
        }
        binding.marginPlus.setOnClickListener {
            if (value < MAX_MARGIN) {
                value += STEP_MARGIN; applyMargin(value)
            }
        }
    }

    private fun applyMargin(v: Int) {
        prefs.margin = v
        dirty = true
        binding.marginValue.text = v.toString()
        binding.marginMinus.isEnabled = v > MIN_MARGIN
        binding.marginPlus.isEnabled = v < MAX_MARGIN
    }

    // ---------------------------------------------------------------- switches
    private fun setupBottomMarginSwitch() {
        binding.bottomMarginSwitch.isChecked = prefs.pageBottomMargin
        binding.bottomMarginSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != prefs.pageBottomMargin) {
                prefs.pageBottomMargin = checked; dirty = true
            }
        }
    }

    private fun setupHyphenationSwitch() {
        binding.hyphenationSwitch.isChecked = prefs.hyphenation
        binding.hyphenationSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != prefs.hyphenation) {
                prefs.hyphenation = checked; dirty = true
            }
        }
    }

    private fun setupPageTurnAnimSwitch() {
        binding.pageTurnAnimSwitch.isChecked = prefs.pageTurnAnimation
        binding.pageTurnAnimSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != prefs.pageTurnAnimation) {
                prefs.pageTurnAnimation = checked; dirty = true
            }
        }
    }
}
