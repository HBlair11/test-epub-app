# v29 — Drawer icon and label alignment

This patch keeps the app-wide **20dp drawer horizontal padding** unchanged and fixes only the drawer header/item alignment.

## Changes

- Drawer header app icon is now a **24dp × 24dp** view, matching the drawer vector icon view size.
- Added a cropped drawer-specific copy of the existing app foreground artwork so transparent source padding does not make the logo glyph appear smaller than the 24dp vector icons.
- Drawer header app name now uses a **16dp** start margin after the 24dp icon.
- Drawer item icons are now **24dp × 24dp**.
- Drawer item labels use a **16dp** start margin after the icon.
- The existing `app_row_content_padding_h` value of **20dp** is preserved.
- No application architecture, navigation, or version changes were made.

## Files updated

- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/item_drawer.xml`

## Files added

- `app/src/main/res/drawable-nodpi/ic_launcher_foreground_image_drawer.png`
- `docs/V29_DRAWER_ICON_ALIGNMENT_UPDATE.md`
