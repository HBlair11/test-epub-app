# Theme Colors — Single Source of Truth

All Caesura colors are defined in **one file**:

```
app/src/main/res/values/colors.xml
```

Change a color **there** and it propagates everywhere it is referenced. This file
is the palette. Below is the full reference, where each color is used, and how to
re-theme the app.

---

## The Palette

### Brand (round 3)

| Name             | Hex       | Role                                                        |
|------------------|-----------|-------------------------------------------------------------|
| `accent`         | `#D88C9A` | Primary accent — buttons, FAB, links, selected states      |
| `accent_dark`    | `#C6707E` | Pressed / darker accent                                     |
| `secondary`      | `#B48EAE` | Mauve secondary — subtitles, icons                          |
| `pop`            | `#F2D0A9` | Peach — progress bars, highlights                           |
| `pop2`           | `#8E7DBE` | Purple — "completed" badges                                 |
| `light_accent`   | `#F1E3D3` | Cream — alternate surface, reader sepia background         |

### Light theme (day)

| Name                  | Hex       | Role                          |
|-----------------------|-----------|-------------------------------|
| `light_bg`            | `#FFEEF2` | App background                |
| `light_surface`       | `#FFFFFF` | Cards, toolbars               |
| `light_surface_alt`   | `#FFE4F3` | Secondary surface             |
| `light_border`        | `#F1E3D3` | Dividers, card borders        |
| `light_text`          | `#5A4650` | Primary body text             |
| `light_text_muted`   | `#B48EAE` | Secondary text                 |
| `light_text_faint`   | `#D88C9A` | Tertiary / placeholder        |

### Dark theme (night)

| Name                 | Hex       | Role                          |
|----------------------|-----------|-------------------------------|
| `dark_bg`            | `#70566D` | App background                |
| `dark_surface`       | `#84657A` | Cards, toolbars               |
| `dark_surface_alt`   | `#92707F` | Secondary surface             |
| `dark_border`        | `#9E7E8D` | Dividers, card borders        |
| `dark_text`          | `#FFEEF2` | Primary body text             |
| `dark_text_muted`   | `#F1E3D3` | Secondary text                 |
| `dark_text_faint`   | `#B48EAE` | Tertiary / placeholder        |

### Reader themes

| Name                | Hex       | Role                              |
|---------------------|-----------|-----------------------------------|
| `reader_bg_light`   | `#FFEEF2` | Reader background (Light theme)   |
| `reader_bg_sepia`   | `#F1E3D3` | Reader background (Sepia theme)   |
| `reader_bg_dark`    | `#70566D` | Reader background (Dark theme)    |
| `reader_ink_light`   | `#5A4650` | Reader text (Light)               |
| `reader_ink_sepia`   | `#5A4650` | Reader text (Sepia)               |
| `reader_ink_dark`    | `#FFEEF2` | Reader text (Dark)                |

### Functional

| Name                | Hex       | Role                                             |
|---------------------|-----------|--------------------------------------------------|
| `progress_track`   | `#F1E3D3` | Progress bar track                               |
| `progress_fill`    | `#F2D0A9` | Progress bar fill                                |
| `cover_placeholder`| `#FFE4F3` | Empty book cover background                      |
| `cover_text`       | `#B48EAE` | Text on empty covers                             |
| `completed`        | `#8E7DBE` | "Completed" badge                                |
| `scrim`            | `#99000000` | Overlay scrim                                    |
| `white` / `black`  | standard  |                                                  |

---

## Where colors are referenced (so a single edit propagates)

1. **Themes** — `app/src/main/res/values/themes.xml` (light) and
   `app/src/main/res/values-night/themes.xml` (dark). These map palette colors to
   Material theme attributes (`colorPrimary`, `colorSurface`, `colorOnSurface`,
   `android:windowBackground`, `android:textColorPrimary`, etc.). Most of the UI
   uses `?android:textColorPrimary`, `?attr/colorSurface`, `?android:colorBackground`,
   so changing the palette value here re-themes the whole app automatically.

2. **Drawables** — a few drawables hardcode a hex:
   - `drawable/badge_bg.xml` → `#D88C9A` (the progress % badge on grid covers)
   - If you change `accent`, update the hex in `badge_bg.xml` too.

3. **Layouts** — most layouts use theme attributes (`?android:textColorPrimary`,
   `@color/accent`, `@color/secondary`, `@color/pop`, `@color/dark_text`).
   These reference the palette by name, so editing `colors.xml` is enough.

4. **Kotlin (reader)** — the reader's Light/Sepia/Dark background + ink colors
   are hardcoded in `ReaderActivity.kt`:
   ```kotlin
   // ReaderActivity.kt → readerColors() and readerSurface()
   Light  -> bg #FFEEF2, ink #5A4650, surface #FFFFFF
   Sepia  -> bg #F1E3D3, ink #5A4650, surface #E9D9C4
   Dark   -> bg #70566D, ink #FFEEF2, surface #84657A
   ```
   These mirror the palette. If you change `reader_bg_*` / `reader_ink_*` in
   `colors.xml`, also update the matching hex in `readerColors()` /
   `readerSurface()` so the reader matches.

5. **Launcher icon** — `drawable/ic_launcher_foreground_image.png` and the mipmap
   PNGs. The icon is a raster image, not theme-driven; see
   [Customization Guide → App Icon](CUSTOMIZATION_GUIDE.md#app-icon).

---

## How to change one color everywhere

### Example: change the accent from `#D88C9A` to `#C0655E`

1. Open `app/src/main/res/values/colors.xml`.
2. Edit:
   ```xml
   <color name="accent">#C0655E</color>
   <color name="accent_dark">#A44D43</color>   <!-- a darker shade of the new accent -->
   ```
3. Open `app/src/main/res/drawable/badge_bg.xml` and set its `<solid>` color to the
   same `#C0655E`.
4. (Reader only) If you also themed the reader accent, update the matching hex in
   `ReaderActivity.kt` `readerColors()` / `readerSurface()`.
5. Rebuild: `./scripts/build.sh`

That's it — buttons, FAB, selected tabs, selected drawer item, progress tint,
seek bars, and links all pick up the new color.

### How to switch to a completely different palette (e.g. a blue theme)

1. Replace the hex values in `app/src/main/res/values/colors.xml` (keep the
   color **names** the same — only change the hex). Names are referenced by name
   throughout layouts/themes, so renaming would break references.
2. Update the hardcoded hex in `drawable/badge_bg.xml`.
3. Update the reader hex values in `ReaderActivity.kt` (`readerColors()`,
   `readerSurface()`).
4. Rebuild and verify in both Light and Dark mode.

> Tip: keep light/dark variants perceptually paired (light bg dark text, dark bg
> light text). Maintain at least 4.5:1 contrast for body text (WCAG AA).

---

## Adding a brand-new color

1. Add a `<color name="my_new_color">#HEX</color>` entry in `values/colors.xml`.
2. Reference it in a layout: `android:textColor="@color/my_new_color"`.
3. (Optional) Expose it on the theme so it can be overridden per light/dark:
   add an `<item>` in both `values/themes.xml` and `values-night/themes.xml`.
