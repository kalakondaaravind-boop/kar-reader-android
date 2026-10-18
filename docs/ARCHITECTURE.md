# Architecture

KAR READER is a single-module Android app (Kotlin, Jetpack Compose, Material 3) organised by layer and
feature packages. MVVM with unidirectional data flow: Compose screens collect `StateFlow`s from Hilt view
models; view models call repositories; repositories own Room DAOs and files.

```
ui (Compose screens + ViewModels)
   │  StateFlow / suspend calls
data.repo (Library, Reading, Annotation, Flashcard, Notebook, Trash, Profile)   data.transfer (Backup, Sync, LAN)
   │                                                                              │
data.db (Room, 22 tables)   data.files (DocumentStore, DocumentImporter, CoverGenerator)   data.prefs (DataStore)
   │
feature engines (PdfDocumentSession, PdfExporter, EpubSession + KarWebView, CalibreImporter, ScanProcessor, PrivacyFacts, DocumentRenderers)
   │
core (pure Kotlin, unit-tested): Citations, ResearchExport, SpacedRepetition, SmartRules, InkGeometry/ShapeRecognizer,
       TrashPolicy/ReadingStats, SyncMerge/PairingCodes/PinHasher, EpubParser, SafeZipExtractor, FileSignature, Hashing
```

## Key decisions (summary — see `docs/adr/`)

| ADR | Decision |
|---|---|
| 0001 | Single app module with feature packages; split into Gradle modules only when build times demand it. |
| 0002 | PDF rendering with the platform `PdfRenderer`; annotations stored in the database, not written into the PDF; export as a flattened copy. |
| 0003 | EPUB with a small in-house parser + WebView instead of Readium for the first release. |
| 0004 | Offline guarantee enforced by the manifest (no `INTERNET`), merged-manifest removal of library network permissions, and a CI check. |
| 0005 | Bundled ML Kit Latin text recognition for scanning and PDF area highlights; handwriting-to-text is out of scope. |
| 0006 | Sync by snapshot files in a user-chosen folder; Wi-Fi sync only in a separate flavour. |
| 0007 | Soft delete with a 30-day recycle bin for all user content; note version history. |

## Data model

All user data lives in Room (`KarDatabase`, schema exported to `app/schemas`). Books and their files are
shared; everything a person creates is scoped by `profile_id`. Deletable rows carry `deleted_at`; synced rows
carry `modified_at`. Books are identified across devices and backups by the SHA-256 of their content.

Files: `files/library/<id>.<ext>` (imported documents, never modified), `files/covers/` (rebuildable),
`files/staging/` (in-flight imports, cleared at start), `cache/export` and `cache/share` (temporary).

## Background work

Import, export, OCR, backup, restore and sync are interactive and run in view-model coroutines on IO/Default
dispatchers with progress and cancellation. Work interrupted by process death leaves files only in staging,
which is cleaned at the next start. WorkManager is not used yet because no task needs to run deferred.

## Security boundaries

Imported files, archives, EPUB content, backups and sync snapshots are untrusted. See [SECURITY.md](../SECURITY.md).
