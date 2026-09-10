# The Livre Magicae — Future Updates, Product Direction & Master Roadmap

## Purpose

This is the master product and engineering guide for taking **The Livre Magicae** from its current solid offline EPUB reader into a highly polished, privacy-first reading app that is memorable because it is calm, capable, dependable, and personal.

It is a **future-update guide**, not a promise that every feature must be implemented immediately. Each item should be delivered as a small, testable release that preserves the existing EPUB reader foundation.

## Product identity

> **A beautifully designed private library for people who love reading.**
>
> No account. No cloud dependency. No tracking. No advertising. No required network connection. Your books and reading life stay on your device.

The goal is not to become an all-in-one marketplace, social network, or cloud bookshelf. The goal is to become the **offline reader a user does not want to uninstall**.

---

# 1. Product principles

1. **Offline first.** No `INTERNET` permission. Avoid features that require online services.
2. **Private by design.** No accounts, analytics, telemetry, ads, or cloud sync unless the product philosophy is intentionally changed later.
3. **Reader first.** The EPUB reader remains the most protected subsystem.
4. **Optical polish over mathematical sameness.** Consistent visual keylines and optical weight matter more than identical raw dimensions.
5. **Incremental architecture.** Do not rewrite the project to add features. Extract small responsibilities only when related code is already being changed.
6. **User data is sacred.** Room migrations are mandatory; no destructive fallback. Manual metadata edits must never be overwritten by rescans.
7. **Stable positions.** Persist a locator rather than a display page number. Pagination is reflowable and therefore unstable.
8. **Local resilience.** Broken EPUBs should fail gracefully. Corrupt files should never crash the UI.
9. **Quiet interactions.** Avoid excessive animation, badges, gamification, or dialog interruptions.
10. **Easy testing.** Each release should have a narrow behavioral contract and a repeatable regression set.

---

# 2. Honest current-state evaluation

| Area | Current | Long-term target |
|---|---:|---:|
| Core EPUB reading | 8.5/10 | 9.5/10 |
| Offline/privacy | 9.5/10 | 10/10 |
| Library organization | 7.5/10 | 9.5/10 |
| Reader UX | 8/10 | 9.5/10 |
| Navigation | 8/10 | 9/10 |
| Reader customization | 8.5/10 | 9.5/10 |
| Metadata | 7/10 | 9.5/10 |
| Search | 7.5/10 | 9/10 |
| Bookmarks | 7.5/10 | 9/10 |
| Accessibility | 6.5/10 | 9/10 |
| Code organization | 6.5/10 | 8.5/10 |
| Architecture stability | 8/10 | 9/10 |
| Performance potential | 8/10 | 9/10 |
| Maintainability | 7/10 | 9/10 |
| Cool factor | 6.5/10 | 10/10 |

The biggest product insight is that the project does **not** need dozens of features. It needs a smaller set of features executed unusually well.

The biggest developer-side risk is growing responsibility concentration in large activities such as `MainActivity.kt` and `ReaderActivity.kt`, plus a large `EpubParser.kt`. These are warnings for gradual extraction, not reasons to rewrite the app now.

---

# 3. What is already strong

The current project already has a strong EPUB foundation: spine handling, WebView + Caesura pagination, page measurement, progress persistence, navigation, reader history, bookmarks, TOC, in-book search, themes, font controls, line-height, margins, alignment, hyphenation, page-turn animation, screen-on behavior, background parsing, Room persistence, import identity/fingerprinting, and local resource resolution.

The most valuable existing architectural decision is using **reader locations/spine state rather than treating page number as permanent identity**.

The offline/no-network philosophy is also a genuine product differentiator rather than a cosmetic claim.

---

# 4. Master priority roadmap

| Priority | Release | Main outcome | Risk | Notes |
|---|---|---|---|---|
| 0 | Continuous | UI/UX polish foundation | Low | Empty/loading/error/accessibility consistency |
| 1 | v30 | Metadata & Library Polish | Low | Highest-leverage safe polish |
| 2 | v31 | Continue Reading & Home | Low | Habit-forming entry point |
| 3 | v32 | Selection Foundation | Medium | Shared foundation |
| 4 | v33 | Highlights & Notes | High | Major serious-reader feature |
| 5 | v34 | Offline Dictionary | Medium | Builds on selection |
| 6 | v35 | Backup & Restore | Medium | User trust/portability |
| 7 | v36 | Read Aloud / TTS | Medium | Accessibility/convenience |
| 8 | v37 | Reading Stats | Low–Medium | Quiet, local, opt-in |
| 9 | v38 | Smart Collections & Library Intelligence | Medium | Personal shelf behavior |
| 10 | v39 | Search UX Expansion | Medium–High | Richer matching/navigation |
| 11 | v40+ | Reading Nook | Medium | Personal reading workspace |
| 12 | Later | CBZ | Medium | Separate reader activity |
| 13 | Later | PDF | High | Separate fixed-layout reader |
| 14 | Defer | MOBI/AZW3 | High | Only with a defensible parser strategy |

**Release rule:** one release normally has one headline capability. Bundle only when the sub-features share infrastructure and remain easy to test and revert.

---

# 5. The 71-point master improvement list

1. Protect the current WebView + Caesura EPUB reader as the heart of the product.
2. Keep the offline/privacy-first philosophy as the core product identity.
3. Aim to be the reader a user does not want to uninstall, rather than trying to be the only reader.
4. Spend more polish effort on the reader than on adding unrelated formats early.
5. Strengthen immersive reading mode so chrome disappears quietly and predictably.
6. Make reading progress human-readable, such as chapter number plus percentage.
7. Develop reader history into a deliberate power feature, especially around footnotes and search jumps.
8. Improve footnote/endnote handling with contextual popups or sheets and exact return navigation.
9. Prioritize metadata and library quality early because they greatly improve perceived polish.
10. Build a visually rich Book Details page that feels like a book rather than a database record.
11. Make the Library behave more like a personal home/bookshelf than a file browser.
12. Make Continue Reading the fastest path back into the last book.
13. Make collections curated shelves that can contain a book in multiple places.
14. Add smart collections such as Unread, In Progress, Finished, and Recently Read.
15. Expand library search to title, author, series, tags, and description while keeping in-book search distinct.
16. Make search results remember context and return precisely to the match.
17. Build text-selection infrastructure that can power several future features.
18. Make highlights a first-class, visually satisfying interaction.
19. Create a Reading Desk concept around highlights, notes, bookmarks, and jumps.
20. Add an offline dictionary with a lightweight contextual result sheet.
21. Make TTS a deliberate reading mode rather than just another button.
22. Keep reading statistics quiet, useful, local, and non-gamified.
23. Make reading goals optional and never pressure the user.
24. Preserve curated reader themes instead of exposing excessive color configuration too early.
25. Add a one-tap Reset Reading Appearance action.
26. Consider per-book reading appearance settings once global settings are stable.
27. Keep the drawer quiet and understandable in two seconds.
28. Establish a consistent hierarchy between sheets, dialogs, snackbars, and full-screen surfaces.
29. Prefer Snackbar undo over unnecessary destructive confirmations.
30. Avoid confirmation dialogs for reversible, low-risk actions.
31. Treat `MainActivity.kt` as a future extraction target without rewriting it prematurely.
32. Treat `ReaderActivity.kt` as a future extraction target without destabilizing it.
33. Gradually decompose `EpubParser.kt` by responsibility only when parser work warrants it.
34. Keep the existing `data/`, `epub/`, `ui/`, and `util/` package foundations.
35. Keep Room migrations explicit and non-destructive.
36. Watch `BookEntity` size; split conceptual state only when the entity becomes a maintenance problem.
37. Preserve source URI, checksum, source filename, mtime, and fingerprint-based import identity.
38. Add robust duplicate detection using content hash.
39. Make import/rescan feedback clearer and more professional.
40. Create user-friendly malformed/broken EPUB error states.
41. Expand EPUB compatibility testing across EPUB2/EPUB3 and unusual content.
42. Add strong RTL support for both app UI and book content.
43. Improve accessibility with TalkBack labels, scalable UI, focus order, and contrast.
44. Keep touch targets comfortable and consistent.
45. Maintain 24dp icon glyphs inside consistent touch targets and align optically, not just mathematically.
46. Build and maintain a clear design-system spacing scale.
47. Standardize recurring UI component patterns.
48. Keep grid mode visually simple: cover, title, author, subtle progress.
49. Make list mode more information-rich without turning it into a dashboard.
50. Avoid excessive badges and chips that make the library feel noisy.
51. Treat relative dates such as Today/Yesterday as a micro-polish standard.
52. Make finishing a book satisfying without confetti or gamification.
53. Add a strong privacy/about page that clearly explains local-only behavior.
54. Make backup/restore a first-class local feature.
55. Add local library export for user control and portability.
56. Consider local data portability/versioned backups as schemas evolve.
57. Keep WebView security restrictions strong and JavaScript bridges narrow.
58. Treat EPUB HTML/CSS/SVG as untrusted content.
59. Escape all text before HTML/JS injection.
60. Never expose database/file mutation operations to EPUB JavaScript.
61. Keep long-running parsing, searching, cover processing, and heavy work off the main thread.
62. Keep lifecycle-sensitive WebView/Handler callbacks guarded against stale activity state.
63. Use deliberate caching and memory discipline for large books/covers.
64. Preserve page measurement cache correctness across viewport/settings changes.
65. Build the minimum stable regression EPUB/file set and run it for every reader-affecting update.
66. Separate fixed-layout and reflowable format readers rather than forcing them through one reader.
67. Treat CBZ as a simple image pager before attempting broader format support.
68. Treat PDF as a fixed-layout renderer with strict memory limits and resource closing.
69. Defer MOBI/AZW3 until parser/licensing/DRM realities justify the engineering cost.
70. Use micro-interactions to create cool factor through restraint rather than gimmicks.
71. Keep every release small, documented, testable, reversible, and faithful to the privacy-first identity.

---

# 6. v30 — Metadata & Library Polish

## Goal

Make the bookshelf look curated instead of file-manager-like by giving books richer metadata, better covers, editability, and stronger empty states.

## MVP

- Parse title, author(s), series, series index, publisher, publish year, identifiers/ISBN-like values, language, description, subjects/tags.
- Extract covers defensively.
- Generate a placeholder cover when an EPUB has no usable cover.
- Let users edit title, author, series, series index, publisher, publish year, language, identifier, subjects, description, and cover.
- Never overwrite a user's edits during rescan.
- Show useful metadata in Book Details.

## Engineering rules

- Add nullable fields through Room migration.
- Keep covers in app-private storage and store file paths in Room.
- Gate overwrite behavior with `metadataEdited`.
- Preserve source identity and reading state.
- Test install-over migration rather than uninstall/reinstall.

---

# 7. v31 — Continue Reading & Home

## Goal

Opening the app should immediately reconnect the user to their reading life.

## Recommended design

Treat the existing Library landing view as the Home view to avoid creating a needless second navigation architecture.

When a recently opened book exists, show a quiet Continue Reading card near the top with:

- cover
- title
- progress
- one-tap Continue Reading action

The card disappears when there is no recent book and stays out of other shelf views.

## Later Home evolution

- Continue Reading
- Recently Added
- Favorites
- optionally a small In Progress section

Avoid loading every library feature onto Home.

---

# 8. v32 — Selection Foundation

## Goal

Create a stable selection payload without yet building the full annotation/dictionary system.

## Required foundations

- Native WebView text selection.
- Narrow JS bridge.
- Selection text.
- Current spine/href.
- DOM/node paths.
- Start/end offsets.
- Prefix/suffix context.
- Renderer-independent locator model.

## Safety

The bridge must not expose filesystem, Room, delete, import, or settings mutations.

## Downstream consumers

- Highlight
- Note
- Define
- Long-press Search
- Copy-with-context
- Vocabulary saving
- Search result re-location

---

# 9. Highlights & Notes

## MVP

Long-press selection → Highlight / Note.

The locator should survive font size, margins, themes, orientation, and pagination changes as far as practical.

Use `LocatorV1` first:

`bookId + spineHref + selected text + normalized offset + prefix + suffix`

Only pursue full EPUB CFI if real-world requirements justify it.

Add Highlights/Notes to the existing reader overlay rather than inventing another navigation pattern.

Always render clean chapter HTML first and apply highlights exactly once. Escape content before DOM/JS injection.

---

# 10. Offline dictionary

Long-press a word → Define → compact bottom sheet.

Use exact lookup, then case-insensitive/base-form fallback. No online fallback.

Use a bundled, appropriately licensed dictionary database, query it off the main thread, and show attribution in About.

---

# 11. Read Aloud / TTS

## MVP

- system `TextToSpeech`
- foreground-only
- play/pause/stop
- paragraph/sentence chunking
- chapter-end continuation
- audio focus
- clean lifecycle shutdown

Offline speech depends on an installed system engine/voice with offline voice data; the app should not download a voice itself.

Later: sentence highlighting, page/chapter progression, per-book voice/speed settings.

---

# 12. Reading stats

Keep them quiet and reflective.

MVP:

- active reading time
- books finished
- current streak
- time this week
- time this year
- approximate pages/chapters advanced

Treat active time as stable. Page counts are display-only because pagination changes.

Later:

- optional goals
- per-book history
- yearly review
- local export

Use privacy-forward wording: **All reading stats stay on your device.**

---

# 13. Search UX master plan

The existing in-book search is a strong base. Expand it in stages rather than making one risky rewrite.

## In-Book Text Search & Matching

### Keyword Matching

Locate exact words, phrases, and partial terms across the currently open book.

### Context Snippets

Show a short preview sentence or sentence fragment around each result.

### Location Indicators

Show chapter name/section and a stable reading context. Page number is display-only and changes with reflow.

### Pre-filled Active Keywords

When the search menu is reopened during the same reader session, preserve the current query.

### Long-Press to Search

Long-press a word and choose Search from the selection action menu to scan the book immediately.

## One-by-One Match Navigation

### Forward and Back Arrows

Navigate directly to the next or previous match without leaving the reader.

### Text Highlights

Highlight all visible search matches and distinguish the active match.

### Page History Shortcuts

Provide a temporary return-to-origin action based on the existing reader history mechanism.

### Visual Page Thumbnails

Later enhancement only. Useful visually, but more complex and should not destabilize search or pagination.

## Search List & Interface Management

### Session Caching

Keep current results in active memory so navigation does not trigger repeated scans. Invalidate on book/query changes.

### Smart Search History

Remember recent terms locally and show them below the search field.

### Dynamic Filtering & Highlighting

Filter recent search terms while typing and emphasize matching characters.

### Integrated Navigation Panel

Bring search into the same reader navigation family as Contents, Bookmarks, Highlights, and Notes.

### Cross-Format Search Uniformity

Keep the **user interaction model** consistent across EPUB/PDF/CBZ/etc. while allowing each format to use a technically appropriate search engine. Do not force a single implementation across incompatible renderers.

## Search implementation order

1. query retention
2. next/previous match
3. context snippets
4. stable chapter/location presentation
5. long-press Search
6. search result highlighting
7. session cache
8. search history
9. integrated navigation panel
10. visual page thumbnails
11. cross-format UX standardization after multiple readers exist

---

# 14. Reading Nook

## Product concept

A **Reading Nook** is a standalone full-screen personal workspace for a book. The user reaches it from Book Details.

It is not a replacement for the reader. The reader is where the user reads; the Nook is where the user records and reflects on the reading life around that book.

## Core workflow

**Bookshelf → Book Details → Reading Nook**

The Nook becomes the book's personal workspace:

> Status → Reading progress → Reading notes/journal → Reading history

## Book information

- existing 3D cover effect
- title and author context
- current Nook book context

## Reading status

- Unread
- Reading
- Read

Store status locally.

## Progress philosophy for this app

The actual reader progress should remain authoritative. Avoid creating a second conflicting manual progress slider. The Nook should present real reading progress, while optional status controls can provide manual state only where needed.

## Reading stamps

Possible restrained local stamps:

- started
- resumed
- finished
- revisited
- favorite moment
- memorable
- reread

Avoid turning them into achievement mechanics.

## Journal

Each book can have a local journal with basic formatting:

- bold
- italic
- underline
- bullet lists
- heading
- normal text

Later:

- dated entries
- journal search
- journal export
- link a journal entry to a highlight/locator

## Relationship with Highlights/Notes

The Nook should surface:

- recent highlights
- notes
- journal entries
- reading stamps
- history

That makes it a reading workspace rather than another duplicate annotation screen.

---

# 15. Backup & restore

This is one of the highest-value trust features.

## Export

Use SAF to export a versioned local backup containing:

- metadata
- collections
- favorites
- reading positions
- bookmarks
- highlights
- notes
- journals
- reading stats
- relevant preferences

Do not embed EPUB files by default.

## Restore

Provide a preview before applying restored data where conflicts can occur.

## Versioning

Backup files need their own schema version so future app releases can migrate backups independently from Room.

---

# 16. Library intelligence

## Continue Reading

Highest priority. One tap back into the latest book.

## Recently Added

Keep current scan behavior and polish its presentation.

## Smart collections

Examples:

- Unread
- In Progress
- Finished
- Favorites
- Recently Read
- Recently Added

## Duplicate detection

Use content checksum as the authoritative duplicate signal.

## Series awareness

Respect series metadata and order books naturally.

## Cover management

Support extracted covers, generated placeholders, user replacements, and later a safe reset-to-source-cover action.

---

# 17. Book Details design direction

The Details screen should feel like a book profile.

Recommended structure:

1. cover
2. title
3. author
4. primary actions
5. progress/resume
6. series
7. description
8. publisher/language/year/identifier/subjects
9. reading history
10. Reading Nook
11. technical file information in a secondary section

Keep low-value technical data visually subordinate.

---

# 18. Reader UX direction

## Chrome

- invisible while reading
- one-tap reveal
- predictable animation
- consistent touch targets

## Settings

Group into:

### Appearance

Theme, font, font size.

### Layout

Line height, margins, alignment, hyphenation.

### Reading

Page-turn animation, keep screen on, future TTS behavior.

Include **Reset Appearance**.

## Themes

Prefer curated readable presets over a giant color editor.

## Per-book settings

Later allow per-book typography when global settings are stable.

---

# 19. Sheets, dialogs, snackbars, alerts

Use a consistent interaction grammar.

## Bottom sheet

Action choices, navigation lists, compact context.

## Dialog

Destructive confirmation, focused input, short high-attention decisions.

## Snackbar

Confirmation, reversible state changes, Undo.

## Full screen

Substantial editing, settings, details, or a complex search surface.

Avoid dialogs for ordinary navigation.

---

# 20. Developer architecture direction

The project is functional and should **not** be rewritten just to follow an architectural trend.

Do not proactively:

- migrate everything to Compose
- introduce a giant Clean Architecture framework
- add a universal `Document` abstraction before multiple formats exist
- refactor `ReaderActivity` merely because it is large

Gradually extract when related code is already being changed.

Possible reader controllers later:

- `ReaderNavigationController`
- `ReaderProgressController`
- `ReaderWebViewController`
- `ReaderOverlayController`
- `ReaderHistoryController`
- `ReaderSnapshotController`

Possible EPUB parser extraction later:

- `EpubContainerParser`
- `EpubPackageParser`
- `EpubMetadataParser`
- `EpubManifestParser`
- `EpubSpineParser`
- `EpubNavigationParser`

---

# 21. Data and migration rules

For every Room schema change:

- real migration
- preserve existing records
- test install-over
- never use destructive fallback

For user-editable metadata:

- preserve edits during rescan
- use an explicit edit-state marker where needed
- do not overwrite blindly

For covers:

- store paths, not large Room BLOBs
- keep files in app-private storage
- do not delete the current cover until a replacement succeeds

For reading positions:

- prefer spine + locator + ratio
- treat page counts as display caches
- invalidate when layout fingerprints change

---

# 22. WebView and EPUB security

Treat every EPUB as untrusted content.

Rules:

- narrow JavaScript bridge
- no database/file mutation exposed to content
- escape all injected text
- restrict file/universal access where compatible with the resolver
- test malformed XHTML/CSS/SVG and links
- keep external navigation deliberate

Local EPUB does not automatically mean trusted EPUB.

---

# 23. Performance direction

Never block the main thread for:

- EPUB parsing
- checksum computation
- cover processing
- dictionary queries
- full-book search
- PDF rendering
- CBZ extraction

Use controlled caching and avoid retaining unnecessary bitmaps/chapters.

Guard lifecycle-sensitive WebView/Handler callbacks against stale activity state.

---

# 24. Accessibility direction

Accessibility is part of the definition of done.

Check:

- TalkBack labels
- touch targets
- scalable text
- contrast
- focus order
- slider value descriptions
- meaningful state descriptions
- RTL behavior

A font-size button should communicate the action and current value, not merely "Button".

---

# 25. Format expansion strategy

## CBZ

Use a dedicated image pager. Guard against path traversal, decompression bombs, absurd image sizes, and excessive extraction.

## PDF

Use a dedicated fixed-layout renderer with strict memory/resource discipline. Close renderer/page/file descriptors and handle encrypted/password-protected files gracefully.

## MOBI/AZW3

Defer until there is a defensible parsing, licensing, and maintenance strategy.

---

# 26. Visual and interaction polish backlog

Prioritize:

- optical icon alignment
- consistent spacing
- quiet card elevation
- restrained corner radii
- predictable text hierarchy
- clear empty states
- clear loading states
- clear error states
- subtle transition continuity
- relative dates
- Undo where safe
- no sudden list jumps
- exact-feeling reader restoration

The drawer rule is a useful example: **20dp outer content padding → 24dp icon box → 16dp gap → aligned text**. Apply the same optical-discipline principle across the application.

---

# 27. Minimum regression test set

Keep a stable set of files:

1. well-formed novel
2. large EPUB
3. image-heavy EPUB
4. missing/bad metadata EPUB
5. EPUB with no cover
6. many-small-spine-item EPUB
7. footnotes/internal-link EPUB
8. RTL book
9. embedded-font EPUB
10. malformed XHTML EPUB
11. partially read book
12. future CBZ
13. future large PDF

For reader-affecting releases, test open/close/reopen, position restore, page turns, theme, font size, margins, orientation, TOC, bookmarks, search, selection, links/footnotes, background/foreground lifecycle.

---

# 28. Release discipline

For every update:

1. Keep the update small.
2. Preserve architecture unless the feature truly requires change.
3. Add/update focused tests.
4. Run a debug build.
5. Verify version information.
6. Verify `INTERNET` permission remains absent.
7. Test install-over when schema changes exist.
8. Test the stable EPUB regression set.
9. Add a short patch note under `docs/`.
10. Ensure every `.kt`, `.kts`, `.xml`, and similar source ends with a newline.

---

# 29. Recommended release map

## v30 — Metadata & Library Polish

Covers, richer metadata, edit book details, better empty states.

## v31 — Continue Reading & Home

Opening the app reconnects the user with the last book.

## v32 — Selection Foundation

Native selection + stable selection payload, without the full annotation/dictionary system yet.

## v33 — Highlights & Notes

First major serious-reader feature.

## v34 — Offline Dictionary

First major selection-powered convenience feature.

## v35 — Backup & Restore

Local portability and trust.

## v36 — Read Aloud

System TTS, foreground-only.

## v37 — Reading Stats

Quiet local reading reflection.

## v38 — Smart Collections & Library Intelligence

Unread, In Progress, Finished, Recently Read, series/duplicate improvements.

## v39 — Search UX Expansion

Query retention, match navigation, snippets, location indicators, long-press Search, highlights, cache/history, integrated panel.

## v40+ — Reading Nook

Personal reading workspace combining status, actual progress, stamps, journal, highlights, notes, and history.

## Later — CBZ / PDF

Separate readers and a lightweight dispatcher. EPUB remains untouched.

## Defer — MOBI/AZW3

Only after a real parser/legal/maintenance strategy exists.

---

# 30. Final product direction

The Livre Magicae has the ingredients for a very strong product because the idea is focused:

**your books, your reading, your data, your device.**

The path to excellence is the combination of:

- excellent EPUB rendering
- effortless return to reading
- beautiful library organization
- robust annotations
- useful offline tools
- careful accessibility
- reliable local persistence
- calm visual design
- strong data ownership
- disciplined engineering

Use this question to judge future features:

> **Does this make reading easier, calmer, more personal, or more trustworthy without compromising the offline/private foundation?**

If yes, it belongs on the roadmap. If no, it is probably feature noise.

## Current execution decision — v32 Dedicated Home

The original v31 "Continue Reading on Library" direction is superseded by the stronger dedicated Home model. Home is now treated as the calm reading launchpad, while Library remains the complete collection and management surface.

### Home priority

1. Continue Reading — most recently opened book, one-tap return.
2. Recently Added — latest local imports.
3. Favorites — a small curated shelf.
4. Top Authors — authors with the most books in the local library, with only the strongest few shelves shown.
5. Top Series — series with the most books in the local library, again limited to a few useful shelves.

Home intentionally omits sorting, grid/list management, bulk actions, and dense filtering so it remains calm and quick to understand.

### Lifecycle policy

- Returning to an existing task should preserve its active screen whenever Android keeps or recreates the task state.
- A new/cold application session should enter Home.
- Removing the task from Recents marks the next launcher-created session for Home.

### Future refinement

When reading-history/statistics infrastructure exists, "Top Authors" and "Top Series" can evolve from library-count ranking to a more personal engagement ranking, but the UI should remain simple.

