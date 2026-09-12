# Selection Toolbar Build Error Fix

## Update

Fixed a Kotlin compilation error in `ReaderActivity.kt` caused by using `return@setOnTouchListener` inside the `View.OnTouchListener` lambda used by the custom selection toolbar drag handler. The ACTION_DOWN branch now handles a missing selection with a normal conditional expression and contains no prohibited labeled return.

## Files updated

- `app/src/main/java/com/epubreader/app/ReaderActivity.kt`
- `docs/SELECTION_TOOLBAR_BUILD_ERROR_FIX.md`

No reader architecture, selection behavior, toolbar styling, spacing, or movement behavior was otherwise changed. App version remains v37 / 1.36.
