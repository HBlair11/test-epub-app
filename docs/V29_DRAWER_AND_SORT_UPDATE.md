# V29 Drawer, Series Sort, and Metadata Refresh UX Update

This V29 update keeps the existing app architecture and makes only focused UI/state changes.

## Metadata refresh

The metadata refresh action now keeps the existing background behavior while showing the SwipeRefresh spinner for a minimum of 1.2 seconds. The background refresh job is not delayed and navigation remains unrestricted.

## Series detail sorting

Series-detail book views now default to `Series` ascending, so books appear in series sequence such as 0.5, 1, 2, 2.5. User-selected sorting remains session-only and can still be changed with the existing sort controls. A new app session returns to the default.

Author-detail book views explicitly default to `Title` ascending, preserving the existing intended behavior.

## Drawer

The drawer app name remains 20sp and drawer item labels are 20sp so they match. Drawer icons are 28dp while the header app icon is 36dp. Drawer rows use 16dp vertical padding. A subtle divider with 8dp breathing room separates Collections from Folders/Settings.
