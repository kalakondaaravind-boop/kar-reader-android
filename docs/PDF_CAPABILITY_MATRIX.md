# PDF capability matrix

Engine: Android `PdfRenderer` (rendering) + `PdfDocument` (writing new files) + bundled ML Kit (text recognition).

| Capability | Supported | How | Notes |
|---|---|---|---|
| Render pages, zoom, scroll | Yes | PdfRenderer, bounded LRU bitmap cache | Max render width 2400 px |
| Password-protected PDFs | No | — | Shown as a clear error |
| Outline / links | No | — | PdfRenderer exposes neither below Android 15 |
| Text selection / search | No | — | Area highlights use OCR instead |
| Ink, highlighter, eraser | Yes | Stored in Room, normalized coordinates | Survive zoom and screen size changes |
| Area highlight with text | Yes | OCR of the selected region | Latin scripts |
| Undo / redo | Yes | Per-session command stack | |
| Save a copy with annotations | Yes | Re-rendered image pages + drawn annotations | Original untouched; copy verified by re-opening; text not selectable |
| True PDF annotation objects | No | — | Needs a PDF editing library |
| Rotate / reorder / delete / split / merge pages | No | — | Needs a PDF editing library |
| Form filling, signatures | No | — | |
| Redaction | No | — | Never simulated |
| Document-change detection | Partial | `document_hash` saved with each stroke | Warning UI not yet shown |
