# v29 Drawer Icon Optical Alignment Update

This update keeps the existing drawer/app-wide 20dp content keyline unchanged. It fixes the visual size mismatch between the drawer app icon and 24dp vector icons by adding a drawer-only copy of the app artwork cropped to its actual non-transparent bounds.

## Changes
- Added `app/src/main/res/drawable-nodpi/ic_launcher_drawer.png`, cropped from `ic_launcher_foreground_image.png` using its alpha bounds (`42,46` to `478,482`), reducing the source canvas from 512x512 to 436x436.
- Updated the drawer header icon in `activity_main.xml` to 24dp x 24dp and to use the drawer-specific cropped artwork.
- Updated drawer item icons in `item_drawer.xml` to 24dp x 24dp.
- Standardized the icon-to-label gap to 16dp in both the drawer header and drawer items.
- Preserved `@dimen/app_row_content_padding_h` at 20dp; no app-wide spacing was removed or changed.
- Kept versionCode 29 / versionName 1.28.

## Why
The original 512x512 foreground PNG contains transparent margins, so a 24dp ImageView scales the full canvas and makes the visible logo appear smaller than vector paths occupying more of their 24x24 viewport. The drawer-specific crop removes only that unused transparent canvas without altering the launcher artwork used elsewhere.
