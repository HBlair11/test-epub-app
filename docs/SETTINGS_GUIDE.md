# Adding a New App-Level Setting (like Screen On)

This guide shows how to add a new on/off toggle to the Settings view, following
the exact pattern used by Patch 11's **Screen On** setting. The app uses
`SharedPreferences` via `PrefsManager`, and the Settings view is built
procedurally in `MainActivity.showSettingsView()` (not from an XML preference
screen).

## The five places to touch

### 1. `PrefsManager.kt` — store the value

```kotlin
// Add a private key + a public property.
private const val KEY_MY_SETTING = "pref_my_setting"

var mySetting: Boolean
    get() = prefs.getBoolean(KEY_MY_SETTING, false) // default OFF
    set(value) {
        prefs.edit().putBoolean(KEY_MY_SETTING, value).apply()
    }
```

The `mySetting` getter/setter is all you need elsewhere — this mirrors exactly
how `keepScreenOn` is implemented in `PrefsManager.kt`. The default value in
`getBoolean(..., false)` is the fresh-install default. There is no reactive
flow to notify here: the toggle's own `setOnCheckedChangeListener` is what
triggers any follow-up behavior (see step 4), and any other screen that reads
the preference just reads `prefs.mySetting` directly when it needs the value
(e.g. in `onResume()`).

### 2. `strings.xml` — the label and summary

```xml
<string name="settings_my_setting">My Setting</string>
<string name="settings_my_setting_summary">What it does, in one line.</string>
```

### 3. `MainActivity.kt` — the toggle UI in Settings

Add a builder method modeled exactly on `addScreenOnToggle()` (see
`MainActivity.kt` around line 1124) and call it from `showSettingsView()`,
right after `addScreenOnToggle()`. Settings rows are added directly to
`binding.emptyState` (the same container used for the Folders/Settings empty
state) and tracked in `dynamicEmptyChildren` so they get cleaned up when the
view changes. Use `SwitchCompat` — the app theme is Material2
(`Theme.MaterialComponents.DayNight.NoActionBar`), so `MaterialSwitch` does not
theme correctly here.

```kotlin
private fun addMySettingToggle() {
    val row = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, 12.dp(), 0, 0)
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }
    val labelColumn = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        layoutParams = android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { weight = 1f }
    }
    android.widget.TextView(this).apply {
        text = getString(R.string.settings_my_setting)
        setTextColor(themeColor(android.R.attr.textColorPrimary))
        textSize = 15f
        labelColumn.addView(this)
    }
    android.widget.TextView(this).apply {
        text = getString(R.string.settings_my_setting_summary)
        setTextColor(themeColor(android.R.attr.textColorSecondary))
        textSize = 12f
        labelColumn.addView(this)
    }
    val switch = androidx.appcompat.widget.SwitchCompat(this).apply {
        isChecked = prefs.mySetting
    }
    switch.setOnCheckedChangeListener { _, isChecked ->
        prefs.mySetting = isChecked
        onMySettingChanged(isChecked)
    }
    row.addView(labelColumn)
    row.addView(switch)
    binding.emptyState.addView(row)
    dynamicEmptyChildren.add(row)
}
```

Call `addMySettingToggle()` from inside `showSettingsView()`, right after the
existing `addScreenOnToggle()` call.

### 4. The behavior — `onMySettingChanged(value)`

This depends on what the setting does:

- **Simple UI-only** (e.g. a preference read at render time): nothing extra —
  the next render reads `prefs.mySetting`.
- **App-wide side effect** (like Screen On): create a small controller class
  (see `KeepScreenOnController.kt`) and wire it into the relevant activities'
  `onResume` / `onPause` / `onUserInteraction`. Call its `refresh()` from the
  toggle listener so the change takes effect immediately without leaving
  Settings.

### 5. (Optional) observe changes

If another part of the app needs to react live (e.g. the reader needs to
re-render when a preference changes while it's open), the simplest approach is
for that activity to re-read `prefs.mySetting` in its own `onResume()` — it
will pick up the latest value when the user returns from Settings. For a
genuinely live change while a screen is on top, have the toggle listener
call a method on that screen directly (the way `addScreenOnToggle()` calls
`keepScreenOnController.refresh()`), rather than relying on a shared flow.

## Checklist for a new toggle

- [ ] `PrefsManager`: key + `Boolean` property with the right default
- [ ] `strings.xml`: label + summary
- [ ] `MainActivity.showSettingsView()`: `addMySettingToggle()` call
- [ ] `addMySettingToggle()` builder (SwitchCompat, MATCH_PARENT row)
- [ ] `onMySettingChanged(value)` — the actual behavior
- [ ] If app-wide: controller + lifecycle wiring in each relevant activity
  (`onResume` arms, `onPause` releases, `onUserInteraction` re-arms)
- [ ] Rebuild: `./gradlew assembleDebug --no-daemon --console=plain`
