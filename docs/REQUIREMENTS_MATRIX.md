# Requirements matrix

Statuses: NOT STARTED · IN PROGRESS · BLOCKED · IMPLEMENTED — NOT VERIFIED · VERIFIED.
"VERIFIED (unit)" means the logic is covered by passing JVM unit tests in CI; device behaviour is still unverified.
No feature is VERIFIED end-to-end yet: the app has not been run on a device or emulator.

| ID | Requirement | Pri | Location | Tests | Status | Known limitations |
|---|---|---|---|---|---|---|
| R-OFF-1 | Standard build has no network access | P0 | `AndroidManifest.xml`, CI step | CI `aapt dump permissions` | **VERIFIED (CI)** | Sync build intentionally has INTERNET |
| R-IMP-1 | Import PDF/EPUB into app storage with staging, hashing, validation | P0 | `DocumentImporter` | `FileSignatureTest` | IMPLEMENTED — NOT VERIFIED | Open-in-place (SAF URI without copy) not implemented |
| R-IMP-2 | Duplicate detection by content hash | P0 | `DocumentImporter.commit` | — | IMPLEMENTED — NOT VERIFIED | Duplicates are skipped; no "keep both" option |
| R-ZIP-1 | Safe ZIP import with preview, limits, traversal protection | P1 | `SafeZipExtractor`, `LibraryViewModel` | `SafeZipExtractorTest`, `ZipPathsTest` | **VERIFIED (unit)** core; UI NOT VERIFIED | Limits: 2,000 entries, 1 GiB/entry, 4 GiB total, ratio 200 |
| R-LIB-1 | Library grid/list, search, sort, filters, favorites | P0 | `LibraryScreen` | — | IMPLEMENTED — NOT VERIFIED | |
| R-LIB-2 | Custom metadata, ratings, tags (separate from file) | P1 | `LibraryRepository.updateDetails` | — | IMPLEMENTED — NOT VERIFIED | Original metadata never written back to files |
| R-LIB-3 | Collections and smart collections | P1 | `CollectionEditorScreen`, `SmartRules` | `SmartRulesTest` | **VERIFIED (unit)** rules; UI NOT VERIFIED | |
| R-EPUB-1 | Open EPUB 2/3, TOC, chapter navigation | P0 | `EpubParser`, `EpubReaderScreen` | `EpubParserTest` | **VERIFIED (unit)** parser; reader NOT VERIFIED | Fixed-layout shown as reflowable; no paginated mode |
| R-EPUB-2 | Per-book typography and themes | P0 | `readerCss`, `EpubSettingsSheet` | — | IMPLEMENTED — NOT VERIFIED | Font family choice not implemented |
| R-EPUB-3 | EPUB highlights, notes, saved words, search in chapter | P1 | `KarWebView`, `READER_JS` | — | IMPLEMENTED — NOT VERIFIED | Depends on `evaluateJavascript` running under a `script-src 'none'` CSP — must be confirmed on device. Whole-book search not implemented |
| R-PDF-1 | Render PDF, zoom, go to page, position restore | P0 | `PdfDocumentSession`, `PdfReaderScreen` | — | IMPLEMENTED — NOT VERIFIED | No outlines/links (PdfRenderer limits) |
| R-PDF-2 | Ink, highlighter, eraser, undo/redo | P0 | `PdfReaderViewModel` | `InkGeometryTest` | IMPLEMENTED — NOT VERIFIED | No pressure sensitivity |
| R-PDF-3 | Area highlight with on-device OCR text | P1 | `PdfReaderViewModel.highlightArea` | — | IMPLEMENTED — NOT VERIFIED | Latin scripts only |
| R-PDF-4 | Save a copy with annotations, verified, original untouched | P0 | `PdfExporter` | — | IMPLEMENTED — NOT VERIFIED | Copy is image-based (text not selectable) |
| R-PDF-5 | Page rotate/reorder/delete/split/merge, forms | P1 | — | — | NOT STARTED | Needs a PDF editing library (ADR 0002) |
| R-BM-1 | Bookmarks (PDF and EPUB) | P0 | `ReadingRepository` | — | IMPLEMENTED — NOT VERIFIED | |
| R-PRIV-1 | Privacy dashboard from real permissions and storage | P1 | `PrivacyFacts`, `PrivacyScreen` | — | IMPLEMENTED — NOT VERIFIED | |
| R-PRIV-2 | App lock (biometric/device credential), FLAG_SECURE | P1 | `AppLockManager`, `MainActivity` | — | IMPLEMENTED — NOT VERIFIED | Not encryption; relies on Android device encryption |
| R-PRIV-3 | Private collection | P1 | `BookStateEntity.isPrivate` | — | IMPLEMENTED — NOT VERIFIED | Notes on private books still appear in the Notes tab |
| R-PRIV-4 | Delete all data | P1 | `PrivacyViewModel.deleteEverything` | — | IMPLEMENTED — NOT VERIFIED | |
| R-CIT-1 | Citations APA/MLA/Chicago, copy quote with citation | P1 | `Citations`, `CitationSheet` | `CitationsTest` | **VERIFIED (unit)** formatting; UI NOT VERIFIED | Titles not auto-converted to sentence case |
| R-EXP-1 | Export Markdown, BibTeX, Obsidian, CSV | P1 | `ResearchExport`, `ExportScreen` | `ResearchExportTest` | **VERIFIED (unit)** formatting; UI NOT VERIFIED | |
| R-EXP-2 | Printable notes summary PDF | P1 | `DocumentRenderers.notesSummaryPdf` | — | IMPLEMENTED — NOT VERIFIED | |
| R-EXP-3 | Quote card image via share menu | P2 | `DocumentRenderers.quoteCard` | — | IMPLEMENTED — NOT VERIFIED | |
| R-HL-1 | Highlight colour meanings, filter by meaning | P1 | `ColorMeaningEntity`, `NotesScreen` | — | IMPLEMENTED — NOT VERIFIED | |
| R-FC-1 | Flashcards from highlights and words, SM-2 scheduling | P1 | `SpacedRepetition`, `ReviewScreen` | `SpacedRepetitionTest` | **VERIFIED (unit)** scheduling; UI NOT VERIFIED | |
| R-FOC-1 | Focus sessions with optional DND | P2 | `FocusScreen` | — | IMPLEMENTED — NOT VERIFIED | Timer does not survive process death |
| R-STAT-1 | Statistics, streaks, goals, year in reading | P2 | `ReadingStats`, `StatsScreen` | `RetentionAndSyncTest` | **VERIFIED (unit)** maths; UI NOT VERIFIED | Only on-screen reading time is counted |
| R-WID-1 | Widgets: continue reading, goal, flashcards due | P2 | `widget/Widgets.kt` | — | IMPLEMENTED — NOT VERIFIED | |
| R-SCAN-1 | Camera scan, deskew, OCR, searchable PDF | P2 | `ScanScreen`, `ScanProcessor` | — | IMPLEMENTED — NOT VERIFIED | Manual corners (no automatic edge detection); hidden text layer drawn at alpha 1 must be checked in other PDF apps |
| R-CAL-1 | Calibre library import | P2 | `CalibreImporter` | — | IMPLEMENTED — NOT VERIFIED | Calibre custom columns not imported |
| R-SYNC-1 | Folder sync | P1 | `SyncManager`, `TransferEngine` | `RetentionAndSyncTest` (merge rules) | **VERIFIED (unit)** merge; device NOT VERIFIED | Syncs the current profile; books must exist on both devices |
| R-SYNC-2 | Wi-Fi sync (sync flavour only) | P2 | `src/sync/.../LanSyncModule.kt` | `RetentionAndSyncTest` (HMAC) | IMPLEMENTED — NOT VERIFIED | Authenticated, not encrypted |
| R-BIN-1 | Recycle bin, 30-day retention | P0 | `TrashRepository` | `RetentionAndSyncTest` | **VERIFIED (unit)** policy; UI NOT VERIFIED | |
| R-HIST-1 | Note version history | P0 | `AnnotationRepository.saveNote` | — | IMPLEMENTED — NOT VERIFIED | |
| R-BAK-1 | Backup/restore, merge and replace, checksum | P0 | `BackupManager`, `TransferEngine` | — | IMPLEMENTED — NOT VERIFIED | |
| R-EINK-1 | E-ink mode | P2 | `KarTheme`, readers | — | IMPLEMENTED — NOT VERIFIED | Generic flash refresh; no vendor SDK |
| R-KEY-1 | Page turns: volume keys, keyboard, BT remotes | P2 | `PageTurnBus`, `MainActivity` | — | IMPLEMENTED — NOT VERIFIED | |
| R-STY-1 | Stylus notebooks, lasso, shapes, palm rejection | P2 | `NotebookScreen`, `ShapeRecognizer` | `InkGeometryTest` | **VERIFIED (unit)** geometry; UI NOT VERIFIED | Handwriting-to-text BLOCKED (needs network model download) |
| R-PROF-1 | Family profiles with optional PIN | P2 | `ProfileRepository`, `ProfilesScreen` | `RetentionAndSyncTest` (PIN hashing) | IMPLEMENTED — NOT VERIFIED | |
| R-WS-1 | Multi-document workspace and comparison | P1 | — | — | NOT STARTED | Planned; mockup exists |
| R-SRCH-1 | Unified full-text search | P1 | — | — | NOT STARTED | Library search covers titles, authors, tags, series |
| R-OCR-1 | OCR of existing scanned PDFs | P2 | — | — | NOT STARTED | Engine exists (`OnDeviceOcr`) |
| R-TTS-1 | Text-to-speech, dictionary | P2 | — | — | NOT STARTED | |
| R-HW-1 | Handwriting recognition | P2 | — | — | BLOCKED | No offline engine without downloading models |
