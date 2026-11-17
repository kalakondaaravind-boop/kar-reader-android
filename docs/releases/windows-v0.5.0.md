## KAR READER for Windows 0.5.0 — first Windows build (pre-release)

The offline reader and research app, now on Windows (64-bit, Windows 10 or 11), driven by mouse and keyboard.
Everything runs on your computer. The only optional feature that uses the internet is signing in to your Microsoft Teams calendar.

### Which file to download
- **KAR-READER-Windows-0.5.0-Setup.exe** — the installer. Start with this one. It installs for your user only (no administrator needed).
- **KAR-READER-Windows-0.5.0.msi** — the same app as an MSI, for IT-managed installs.
- **SHA256SUMS-windows.txt** — checksums to verify the downloads.

**The installer is not code-signed**, so Windows SmartScreen will say “Windows protected your PC”. Choose *More info → Run anyway*.

### What it does
- **Library** for PDF and EPUB: search inside a book and across all books, highlights, bookmarks, notes, contents, zoom, citations (APA and others), reading statistics and focus timer, spaced-repetition review cards, recycle bin (nothing is ever purged automatically), backup and restore.
- **Transcribe**: record or import WAV, MP3, AIFF or AU; on-device speaker separation and speech recognition. Automatic mode covers English, Hindi and Hinglish; other languages when you choose them. Hindi in Devanagari or Roman letters. Rename and merge speakers, edit lines, playback, export TXT, Markdown, SRT, VTT, CSV, JSON.
- **Meeting on this computer**: records your microphone *and* what the computer plays (the other people on Teams, Zoom, Meet…), then tells the voices apart.
- **Minutes of meeting** from any transcript, in the layout: greeting, Particulars table, Agenda, Discussion, Key decisions, Action points table (#, Action point, Responsibility, Target date, Status, Notes) and a colour key. Edit everything, then *Copy for email* or save as Word (.doc), web page or text.
- **Microsoft Teams**: import a Teams transcript (.vtt); optionally fill minutes from your Teams calendar.

### Honest limits
- **Not yet tried on a real Windows PC with a microphone and a live call.** The build, reading, speech (English two-speaker and Hindi), minutes, audio-mixing and Teams-file checks passed in CI on a Windows machine, and the app was started and photographed there. The CI machine has no sound card, so **system-audio capture is untested** and **microphone recording is untested**. If Windows won't share the computer's audio, the app says so and records only the microphone.
- **Minutes are a draft written by simple rules** (cue words, dates such as “tomorrow”, “Friday”, “16 Nov”), not by an AI model. Agenda and discussion are extracted sentences, owners come from speaker names or a name in the sentence. Always read and correct them before sending.
- **Teams calendar sign-in is untested against Microsoft.** It needs an app registration (Application client ID, public client, delegated *Calendars.Read*) from you or your IT team, entered in Settings. The Teams transcript import and calendar-response reading are tested only with sample data.
- *Copy for email* is meant to paste with the tables intact; this has not been tried in Outlook. If it pastes as plain text, use *Save as → Word* instead.
- EPUBs show text only (no images or complex layout). M4A, FLAC and OGG can't be imported yet (WAV, MP3, AIFF, AU can). No DAISY, no watch folders.
- Backups are not compatible with the Android app and there is no sync with it yet.
- No cloud AI chat or summaries, by design.
- Tell people before recording them; laws on recording calls differ.
