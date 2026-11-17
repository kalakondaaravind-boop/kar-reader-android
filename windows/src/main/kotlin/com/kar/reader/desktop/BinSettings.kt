package com.kar.reader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kar.reader.core.domain.CitationStyle
import com.kar.reader.core.domain.TrashPolicy
import java.awt.Desktop
import java.io.File

@Composable
fun BinScreen(state: AppState) {
    var confirmBook by remember { mutableStateOf<Book?>(null) }
    var confirmTr by remember { mutableStateOf<TranscriptMeta?>(null) }
    val books = state.binBooks
    val trs = state.data.transcripts.filter { it.deletedAt != 0L }
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("Recycle bin")
        Muted("Removed items wait here until you delete them for good. KAR READER never empties the bin by itself.")
        if (books.isEmpty() && trs.isEmpty()) Empty("The bin is empty", "Books and transcripts you remove appear here first.")
        books.forEach { b ->
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Text(b.title, style = MaterialTheme.typography.titleSmall); Muted("Book · removed ${formatDate(b.deletedAt)} · its highlights, bookmarks and cards are kept with it") }
                    GhostButton("Restore", { state.restore(b.id) })
                    GhostButton("Delete forever", { confirmBook = b })
                }
            }
        }
        trs.forEach { t ->
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Text(t.title, style = MaterialTheme.typography.titleSmall); Muted("Transcript · removed ${formatDate(t.deletedAt)}") }
                    GhostButton("Restore", { state.update { d -> d.copy(transcripts = d.transcripts.map { if (it.id == t.id) it.copy(deletedAt = 0) else it }) } })
                    GhostButton("Delete forever", { confirmTr = t })
                }
            }
        }
    }
    confirmBook?.let { b -> ConfirmDialog("Delete “${b.title}” forever?", "The book file, its highlights, bookmarks, flashcards and reading history are removed from this computer. This can't be undone.", "Delete forever", danger = true, onConfirm = { state.deleteForever(b.id) }, onDismiss = { confirmBook = null }) }
    confirmTr?.let { t -> ConfirmDialog("Delete “${t.title}” forever?", "The transcript and its audio recording are removed from this computer. This can't be undone.", "Delete forever", danger = true, onConfirm = { TranscriptFiles.deleteForever(state, t) }, onDismiss = { confirmTr = null }) }
}

@Composable
fun SettingsScreen(state: AppState) {
    val s = state.data.settings
    fun set(change: (Settings) -> Settings) = state.update { it.copy(settings = change(it.settings)) }
    var restoring by remember { mutableStateOf<File?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Title("Settings")
        Section("Appearance") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system" to "Follow Windows", "light" to "Light", "dark" to "Dark").forEach { (k, v) -> Chip(v, s.theme == k) { set { it.copy(theme = k) } } } }
        }
        Section("Citations") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CitationStyle.entries.forEach { c -> Chip(c.label, s.citation == c.name) { set { it.copy(citation = c.name) } } } }
            Muted("Used when you copy a reference or a quote with its citation, and in Markdown exports.")
        }
        Section("Transcripts") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Hindi shown in")
                Chip("Devanagari (हिन्दी)", !s.hindiRoman) { set { it.copy(hindiRoman = false) } }
                Chip("Roman letters (Hinglish)", s.hindiRoman) { set { it.copy(hindiRoman = true) } }
            }
            Muted("Roman letters are a letter-by-letter conversion of the recognised Hindi, so spellings can differ from how you would type them.")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = s.removeFillers, onCheckedChange = { v -> set { it.copy(removeFillers = v) } })
                Text("Remove English filler words (um, uh, er) from shown and exported text")
            }
        }
        Section("Read aloud") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Speed")
                Slider(value = s.ttsRate.toFloat(), onValueChange = { v -> set { it.copy(ttsRate = v.toInt()) } }, valueRange = -5f..8f, steps = 12, modifier = Modifier.width(260.dp))
                Text(if (s.ttsRate == 0) "Normal" else if (s.ttsRate > 0) "Faster +${s.ttsRate}" else "Slower ${s.ttsRate}")
            }
            Muted(if (Tts.available) "Uses the voices installed in Windows. Nothing is sent anywhere." else "Read aloud works on Windows only.")
        }
        Section("Your data") {
            Muted("Everything lives in ${Paths.root.absolutePath}")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Open data folder", { runCatching { Desktop.getDesktop().open(Paths.root) } })
                PrimaryButton("Back up everything…", { Backup.export(state) })
                GhostButton("Restore a backup…", { restoring = pickFiles("Restore a backup", "KAR READER backup", "zip", multi = false).firstOrNull() })
            }
            Muted("Backups are .zip files with your library, highlights, notes, cards, transcripts and recordings. Restoring adds what is missing and never overwrites what you have. Backups from the Android app are not compatible yet.")
        }
        Section("About") {
            Text("KAR READER for Windows 0.5.0")
            Muted("Offline by design: no account, no analytics, no network use. Speech models: ${SpeechPaths.description}")
            Muted("Known limits: EPUB is shown as text (images and fancy layout aren't drawn); PDFs are shown as pages with area highlights; transcription quality for Hindi has no punctuation; not yet tested on every Windows setup.")
            Muted("Shortcuts: Ctrl+1…6 switch screens, Ctrl+, settings · in a book: Page Up/Down or Space scroll, ←/→ page or chapter, Ctrl+F search, Ctrl+B bookmark, Ctrl+H highlight, Ctrl +/− zoom.")
        }
    }
    restoring?.let { f -> ConfirmDialog("Restore this backup?", "Items that are missing from your library are added. Nothing you already have is changed or removed.", "Restore", onConfirm = { Backup.restore(state, f) }, onDismiss = { restoring = null }) }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Panel(Modifier.fillMaxWidth().widthIn(max = 900.dp)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() } }
}
