# Selection Toolbar Tap-to-Drag Update

Updated the custom text-selection toolbar so its non-action area can be dragged immediately with a normal touch-and-drag gesture instead of requiring a long press. The Copy, Define, Highlight, and More controls retain their existing click behavior.

## Updated files

- `app/src/main/java/com/epubreader/app/ReaderActivity.kt` — replaced the long-press toolbar movement gesture with immediate free X/Y dragging on the toolbar background and spaces between actions.

The existing native selection-toolbar suppression, More menu, approved toolbar dimensions, app-wide edge margin, and version v37 were preserved.
