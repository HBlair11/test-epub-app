# V29 Drawer Compile and Alignment Fix

## Fixes

- Corrected `DrawerAdapter` to use `RecyclerView.ViewHolder` as the `ListAdapter` view-holder type so the normal drawer rows and divider rows compile together.
- Restored the original drawer label sizing (`15sp`) and row vertical padding (`14dp`).
- Changed the drawer header app name to `15sp` so it matches the drawer view labels.
- Kept the existing drawer header icon at `34dp` and changed drawer section icons to `34dp` so the icon sizes match.
- Preserved the existing app-wide 20dp horizontal content keyline.
- No database, reader, metadata, or navigation behavior changes were made by this compile/alignment fix.
