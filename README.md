# KAR READER

An offline PDF and EPUB reader and research workspace for Android tablets and phones.
No accounts, ads, tracking or uploads. The standard build has **no network permission at all**.

> **Status: DEVELOPMENT BUILD.** The app compiles and its unit tests pass on GitHub Actions, but it has
> not yet been tested on a physical device or emulator. Every feature is marked
> *IMPLEMENTED — NOT VERIFIED* until it passes device testing. See [docs/REQUIREMENTS_MATRIX.md](docs/REQUIREMENTS_MATRIX.md).

## Features

**Reading**
- EPUB 2/3 reader: contents, chapters, per-book text size, line spacing, margins, light/sepia/dark/high-contrast themes, in-chapter search, bookmarks, reading position restore.
- PDF reader: continuous pages, pinch zoom, go to page, bookmarks, pen, highlighter, eraser, undo/redo, area highlights whose text is read on the device, and "save a copy with annotations" (never modifies the original).
- E-ink mode (pure black and white, no animation, periodic full refresh) and page turning with volume keys, keyboards and Bluetooth page-turner remotes.

**Research**
- Highlights with named colour meanings, notes with full version history, saved words.
- Citations in APA 7, MLA 9 and Chicago; copy a quote with its citation.
- Export to Markdown, BibTeX, Obsidian (YAML front matter) and CSV; printable notes-summary PDF.
- Share a highlight as a quote-card image through Android's share menu.
- Stylus notebooks: blank, lined, grid and dotted pages, shape straightening, lasso select/move/copy/recolour/delete, palm rejection.

**Habits**
- Flashcards made from highlights and saved words, scheduled with spaced repetition (SM-2 family).
- Focus sessions with optional Do Not Disturb; reading statistics, streaks, daily goals and a private "year in reading" recap.
- Home-screen widgets: continue reading, reading goal, flashcards due.

**Library and data**
- Safe import of PDF/EPUB files and ZIP archives (path-traversal, size, ratio and entry-count limits; preview before import).
- Calibre library import (read-only; keeps tags, series, ratings, covers). Camera scan to a searchable PDF using on-device text recognition.
- Collections and rule-based smart collections; ratings, tags and edited metadata kept separate from the files.
- Recycle bin (30 days) for books, notes, highlights, bookmarks, ink, notebooks and words.
- Versioned backup/restore (merge or replace, checksum-verified, single transaction).
- Sync between your own devices through a folder you choose (Syncthing, USB, SD card). Direct Wi-Fi sync is only in the separate **KAR READER Sync** build.

**Privacy**
- Privacy dashboard built from the installed app's real permissions and measured storage.
- App lock (fingerprint, face or device PIN), private collection, family profiles with optional PINs.

## Builds

| Build | Application ID | Network permission | Notes |
|---|---|---|---|
| `standard` | `com.kar.reader` | **None** (verified in CI with `aapt dump permissions`) | Default. Folder sync only. |
| `sync` | `com.kar.reader.sync` | `INTERNET`, `ACCESS_WIFI_STATE` | Adds direct Wi-Fi sync on the local network. Installs alongside. |

## Build it yourself

Requirements: JDK 17, Android SDK (compileSdk 35). Then:

```bash
./gradlew assembleStandardDebug          # app/build/outputs/apk/standard/debug/KAR-READER-0.2.0-standard-debug.apk
./gradlew testStandardDebugUnitTest      # unit tests
./gradlew lintStandardDebug
```

Every push to `main` builds both flavours, runs unit tests and lint, checks the standard APK has no network
permission, and uploads the debug APKs as the `KAR-READER-debug-apk` artifact on the Actions run.

### Install a debug APK

1. Open the latest successful run under **Actions → Android CI**, download `KAR-READER-debug-apk` and unzip it.
2. On the tablet, allow installing apps from your file manager, then open the `standard` APK.
   Or with a cable: `adb install KAR-READER-0.2.0-standard-debug.apk`.

## Project layout

```
app/src/main/java/com/kar/reader/
  core/        pure logic (citations, exports, SM-2, smart rules, ink geometry, EPUB parser, safe ZIP), design system, OCR, lock, input
  data/        Room database, repositories, import, backup/sync transfer, preferences
  feature/     engines: PDF session/export, EPUB session/WebView, Calibre, scan, privacy facts, renderers
  ui/          Compose screens and view models
  widget/      Glance home-screen widgets
app/src/standard/  no-network Wi-Fi sync stub      app/src/sync/  Wi-Fi sync implementation + manifest
docs/          architecture, decisions, requirements matrix, security and capability notes
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).
