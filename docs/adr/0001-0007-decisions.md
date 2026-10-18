# Architecture decision records

## ADR 0001 — Single module with feature packages
**Context.** The specification lists many feature modules. The codebase is new and built in one pass.
**Decision.** One `app` module; packages `core`, `data`, `feature`, `ui`, `widget`. Pure logic lives in `core.domain` with no Android imports so it can move to a JVM module later.
**Consequences.** Simpler builds now; module boundaries are enforced by convention, not Gradle.

## ADR 0002 — PDF: platform PdfRenderer, annotations in the database, flattened export
**Context.** Full PDF editing (true annotation objects, page operations, forms, redaction) needs a PDF library such as PDFBox-Android or a commercial SDK. Their licences, size and offline behaviour must be evaluated first.
**Decision.** Render with `android.graphics.pdf.PdfRenderer`. Ink strokes and highlight areas are stored in Room in normalized page coordinates. "Save a copy with annotations" renders each page to an image, draws the annotations, and writes a new PDF with `PdfDocument`; the copy is re-opened to verify page count before it is handed over. Highlight text in PDFs comes from on-device OCR of the selected area because PdfRenderer has no text API below Android 15.
**Consequences.** Originals are never modified. Exported copies are not text-selectable. Page rotation, reorder, split, merge, forms, PDF text search and true annotation export are **not implemented** (see the PDF capability matrix).

## ADR 0003 — EPUB: in-house parser and WebView
**Context.** The spec asks to evaluate Readium first. This build was produced without an emulator to validate Readium's API surface.
**Decision.** A dependency-free parser (`EpubParser`, unit-tested, XXE-hardened) reads container, package, spine and NAV/NCX. Chapters render in a WebView whose every request is served from the archive; non-book requests are refused; publisher scripts are blocked by CSP. The reader's own helpers run through `evaluateJavascript`.
**Consequences.** Reflowable EPUB works scroll-per-chapter; paginated mode, fixed-layout rendering and media overlays are not implemented. Moving to Readium later is possible behind the same screen.

## ADR 0004 — Offline guarantee
**Decision.** The standard manifest declares no network permission and uses `tools:node="remove"` for `INTERNET` and `ACCESS_NETWORK_STATE`, because ML Kit's logging component would otherwise merge them in. CI fails if the standard APK requests either permission. The privacy dashboard reads permissions from the installed package at runtime.

## ADR 0005 — Text recognition
**Decision.** `com.google.mlkit:text-recognition` (bundled Latin model, ships in the APK, no download). Used for scanning and PDF area highlights. Handwriting-to-text is not offered because ML Kit Digital Ink requires downloading models over the network.

## ADR 0006 — Sync without a cloud
**Decision.** Each device writes a JSON snapshot of its current profile (`kar-sync-<deviceId>.json`) into a folder the user picks and merges the others' snapshots: newest change wins; differing note texts keep the loser in note history; books are matched by content hash. Direct Wi-Fi sync (TCP on private addresses, HMAC with a 6-digit pairing code, not encrypted) exists only in the `sync` flavour.

## ADR 0007 — Never lose user data
**Decision.** Deleting moves items to a 30-day recycle bin (`deleted_at`). Purging runs at app start. Editing a note stores the previous text as a version; restoring a version first saves the current text. Restore and sync merges run in a single Room transaction. No destructive migration fallback.
