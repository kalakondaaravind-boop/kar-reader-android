# Changelog

## 0.3.0 — development build (not device-tested)
- Transcribe: record or import audio; on-device speaker separation and multilingual speech recognition
  (sherpa-onnx with Whisper base, pyannote segmentation 3.0 and 3D-Speaker embeddings); rename and merge speakers,
  correct lines, playback, exports (TXT, Markdown, SRT, PDF), save as note, recycle bin.
- Database version 2 (new transcript tables) with an automatic, tested migration from version 1.
- Release workflow publishing signed APKs to GitHub Releases. Release builds are not code-shrunk yet.

## 0.2.0 — development build (not device-tested)
- All screens and features described in the README: library, PDF and EPUB readers, research tools, flashcards,
  statistics, focus sessions, widgets, privacy dashboard, app lock, profiles, recycle bin, backup, sync,
  Calibre import, scanning, e-ink mode, page-turn keys, stylus notebooks.
- Standard and Sync product flavours; CI verifies the standard APK has no network permission.

## 0.1.0 — scaffold (not distributed)
- Gradle project, core document-safety logic (safe ZIP, EPUB parser, file signatures) with unit tests, CI.
