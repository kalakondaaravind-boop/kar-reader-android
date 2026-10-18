# Known limitations

- **Not device-tested yet.** The build compiles and unit tests pass in CI; nothing has been exercised on a device or emulator.
- **EPUB:** scroll-per-chapter only (no paginated mode); fixed-layout books render as reflowable; search covers the current chapter; font-family choice not offered. Highlights and selection rely on the reader's own JavaScript running while publisher scripts are blocked by CSP — confirm on device.
- **PDF:** no text selection, search, outlines or links; annotated copies are image-based; no page operations, forms or password support.
- **OCR:** Latin-script printed text only; no handwriting recognition.
- **Scan:** corners are adjusted by hand; there is no automatic page-edge detection.
- **Sync:** syncs the active profile; each device needs its own copy of the books (matched by content); Wi-Fi sync is authenticated but not encrypted and is only in the Sync build.
- **App lock** hides the app; data is protected by Android's device encryption, not by an extra KAR READER layer.
- **Focus timer** stops if Android ends the app process.
- **Profiles:** removing a profile hides its data but does not delete it.
- **E-ink refresh** uses a generic black/white flash; vendor refresh modes are not used.
- **Not yet built:** multi-document workspace and comparison, unified full-text search, OCR of existing scanned PDFs, text-to-speech, dictionary.
