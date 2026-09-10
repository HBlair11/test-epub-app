# Patch v33 — Continue Reading location + instant book update

- Bumps the app to v33 / 1.32.
- Continue Reading now shows `Location - …` using the EPUB spine/navigation TOC heading, such as `7. Sky-high Rivalry`, `Chapter Five`, or `Epilogue`.
- The TOC-to-spine mapping uses the existing embedded EPUB navigation data and the reader’s existing section-label logic; no synthetic chapter counting is introduced.
- The current TOC heading is persisted with the book so Home can display it without reparsing the EPUB.
- Opening any book now immediately records `last_opened_date` and `is_currently_reading`, so Home’s Continue Reading card switches to the newly opened book as soon as Room invalidates the observed books Flow.
- Existing Highlights/Notes, Offline Dictionary, Home/Library separation, Recently Added ordering fix, legacy chapter-schema compatibility, drawer alignment, and reader pagination remain intact.
- Database migration 11→12 adds only the nullable `current_location` column; no existing data is deleted or rewritten.
