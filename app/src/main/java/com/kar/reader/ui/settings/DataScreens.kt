@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kar.reader.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.ConfirmDialog
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.core.domain.CitationStyle
import com.kar.reader.core.domain.ExportBook
import com.kar.reader.core.domain.ExportHighlight
import com.kar.reader.core.domain.ExportNote
import com.kar.reader.core.domain.ResearchExport
import com.kar.reader.core.model.HighlightColor
import com.kar.reader.data.db.displayAuthor
import com.kar.reader.data.db.displayTitle
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.prefs.AppSettings
import com.kar.reader.data.repo.AnnotationRepository
import com.kar.reader.data.repo.LibraryItem
import com.kar.reader.data.repo.LibraryRepository
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.data.transfer.BackupManager
import com.kar.reader.data.transfer.BackupPreview
import com.kar.reader.data.transfer.LanHostSession
import com.kar.reader.data.transfer.LanSync
import com.kar.reader.data.transfer.MergeReport
import com.kar.reader.data.transfer.RestoreMode
import com.kar.reader.data.transfer.SyncManager
import com.kar.reader.feature.epub.EpubSession
import com.kar.reader.ui.common.Format
import com.kar.reader.ui.common.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private fun MergeReport.describe(): String = buildString {
    append("$added added, $updated updated")
    if (keptAsHistory > 0) append(", $keptAsHistory older note versions kept in history")
    if (missingBooks.isNotEmpty()) append(". Books not on this device (their notes were skipped): ${missingBooks.take(8).joinToString(", ")}${if (missingBooks.size > 8) "…" else ""}")
}

// ---- Backup -----------------------------------------------------------------------------------

@HiltViewModel
class BackupViewModel @Inject constructor(private val backup: BackupManager) : ViewModel() {
    val busy = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    val preview = MutableStateFlow<BackupPreview?>(null)

    fun create(uri: Uri, includeBooks: Boolean) = viewModelScope.launch {
        busy.value = "Creating backup…"
        message.value = runCatching { backup.createBackup(uri, includeBooks) { busy.value = it } }
            .fold({ m -> "Backup saved and verified: ${m.counts["books"]} books, ${m.counts["highlights"]} highlights, ${m.counts["notes"]} notes." }, { "Backup failed: ${it.message}" })
        busy.value = null
    }

    fun open(uri: Uri) = viewModelScope.launch {
        busy.value = "Checking backup…"
        runCatching { backup.preview(uri) }.onSuccess { preview.value = it }.onFailure { message.value = it.message ?: "This file can't be restored." }
        busy.value = null
    }

    fun restore(mode: RestoreMode) = viewModelScope.launch {
        val p = preview.value ?: return@launch
        preview.value = null
        busy.value = "Restoring…"
        message.value = runCatching { backup.restore(p, mode) }.fold({ "Restore complete: ${it.describe()}." }, { "Restore failed, and nothing was changed: ${it.message}" })
        busy.value = null
    }

    fun cancelPreview() { preview.value?.let { backup.discard(it) }; preview.value = null }
}

@Composable
fun BackupScreen(onBack: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    var includeBooks by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    val stamp = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) }
    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.create(it, includeBooks) } }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::open) }

    Column(Modifier.fillMaxSize()) {
        KarTopBar("Backup and restore", onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Create a backup", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("A single file with your notes, highlights, bookmarks, progress, flashcards, notebooks, collections, profiles and settings for each book. You choose where it's saved; it never leaves your device unless you move it.", fontSize = 13.sp)
                SwitchRow("Include the book files", "Makes the backup much larger, but restores the books too", includeBooks, onChange = { includeBooks = it })
                GlossyButton("Create backup…", onClick = { createLauncher.launch("KAR-READER-backup-$stamp.zip") }, icon = Icons.Filled.Backup, enabled = busy == null)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Restore", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("The file is checked first (format, version and checksum). Restoring runs as one step: if anything fails, nothing changes.", fontSize = 13.sp)
                GlossyButton("Choose a backup…", onClick = { openLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }, kind = GlossyKind.PEARL, icon = Icons.Filled.Restore, enabled = busy == null)
            }
            busy?.let { Column { Text(it); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } }
            message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold) }
        }
    }
    preview?.let { p ->
        AlertDialog(
            onDismissRequest = vm::cancelPreview,
            title = { Text("Restore this backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Made ${Format.date(p.manifest.createdAt)} with KAR READER ${p.manifest.appVersion}")
                    Text(p.manifest.counts.filterValues { it > 0 }.entries.joinToString(" · ") { "${it.value} ${it.key}" }, fontSize = 13.sp)
                    Text(if (p.manifest.includesBookFiles) "Includes book files." else "Book files not included: notes for books that aren't on this device will be skipped.", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Merge keeps everything on this device and adds what's new. Replace removes this device's notes, highlights and progress first (books stay).", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { vm.restore(RestoreMode.MERGE) }) { Text("Merge") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmReplace = true }) { Text("Replace…", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = vm::cancelPreview) { Text("Cancel") }
                }
            },
        )
        if (confirmReplace) {
            ConfirmDialog(
                "Replace this device's notes?", "Notes, highlights, bookmarks, progress, flashcards and notebooks on this device will be replaced by the backup's. This can't be undone, so consider making a backup first.",
                "Replace", onConfirm = { vm.restore(RestoreMode.REPLACE) }, onDismiss = { confirmReplace = false }, destructive = true,
            )
        }
    }
}

// ---- Sync ---------------------------------------------------------------------------------------

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val sync: SyncManager,
    private val prefs: AppPreferences,
    val lan: LanSync,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val busy = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    val host = MutableStateFlow<LanHostSession?>(null)

    fun chooseFolder(uri: Uri) = viewModelScope.launch {
        runCatching { sync.takePersistablePermission(uri) }
        prefs.setSyncFolder(uri.toString())
        syncNow()
    }

    fun forgetFolder() = viewModelScope.launch { prefs.setSyncFolder(null) }

    fun syncNow() = viewModelScope.launch {
        busy.value = "Syncing…"
        message.value = runCatching { sync.syncNow() }.fold({ r ->
            if (r.devicesSeen.isEmpty()) "Your changes were written to the folder. No other devices have synced there yet."
            else "Synced with ${r.devicesSeen.joinToString(", ")}. ${r.merge?.describe() ?: ""}"
        }, { "Sync failed: ${it.message}" })
        busy.value = null
    }

    fun startHosting() = viewModelScope.launch {
        runCatching {
            val session = lan.host { busy.value = it }
            host.value = session
            val report = session.awaitPeer()
            message.value = "Wi-Fi sync complete: ${report.describe()}."
        }.onFailure { if (it !is java.net.SocketException) message.value = "Wi-Fi sync stopped: ${it.message}" }
        host.value = null
        busy.value = null
    }

    fun stopHosting() { host.value?.cancel(); host.value = null; busy.value = null }

    fun join(address: String, code: String) = viewModelScope.launch {
        message.value = runCatching { lan.join(address, code) { busy.value = it } }.fold({ "Wi-Fi sync complete: ${it.describe()}." }, { "Wi-Fi sync failed: ${it.message}" })
        busy.value = null
    }
}

@Composable
fun SyncScreen(onBack: () -> Unit, vm: SyncViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val host by vm.host.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::chooseFolder) }

    Column(Modifier.fillMaxSize()) {
        KarTopBar("Sync between your devices", subtitle = "No cloud · no account", onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Folder sync", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("Recommended · keeps zero network access", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("KAR READER writes your current profile's changes to a folder you choose and merges what your other devices left there. Tools you already trust, like Syncthing, a USB drive or a memory card, carry the folder between devices. Books are matched by their content, so import the same files on each device.", fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                val folderName = settings.syncFolderUri?.let { Uri.parse(it).lastPathSegment?.substringAfterLast(':') }
                Text(if (folderName != null) "Folder: $folderName · last synced ${Format.relative(settings.lastSyncAt.takeIf { it > 0 })}" else "No folder chosen yet", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlossyButton(if (folderName == null) "Choose folder…" else "Change folder…", onClick = { folderLauncher.launch(null) }, kind = GlossyKind.PEARL, icon = Icons.Filled.Folder, compact = true)
                    if (folderName != null) GlossyButton("Sync now", onClick = vm::syncNow, icon = Icons.Filled.Sync, compact = true, enabled = busy == null)
                }
                Text("If both devices changed the same note, the newer text is kept and the other is saved in that note's history.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Direct Wi-Fi sync", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                if (!vm.lan.isAvailable) {
                    Text(
                        "Android needs network permission for this, even on a home network. To keep this app's “no internet access” guarantee, Wi-Fi sync is only in the separate KAR READER Sync build, which installs alongside this one. Folder sync above works without it.",
                        fontSize = 13.sp,
                    )
                } else {
                    Text("Both devices must be on the same Wi-Fi and running KAR READER Sync. Data is checked with the pairing code but not encrypted, so use networks you trust.", fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    val h = host
                    if (h != null) {
                        Text("On your other device, enter:", fontWeight = FontWeight.SemiBold)
                        Text("Address  ${h.address}", fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                        Text("Code  ${h.code}", fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        GlossyButton("Stop", onClick = vm::stopHosting, kind = GlossyKind.PEARL, compact = true)
                    } else {
                        GlossyButton("Receive from another device", onClick = vm::startHosting, icon = Icons.Filled.Wifi, enabled = busy == null)
                        Spacer(Modifier.height(12.dp))
                        Text("Or send to a device that is waiting:")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(address, { address = it.trim() }, label = { Text("Address") }, singleLine = true, modifier = Modifier.weight(1.4f))
                            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        GlossyButton("Sync", onClick = { vm.join(address, code) }, enabled = busy == null && address.contains(':') && code.length == 6, compact = true)
                    }
                }
            }
            busy?.let { Column { Text(it); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } }
            message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ---- Research export -----------------------------------------------------------------------------

@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
    private val library: LibraryRepository,
    private val annotations: AnnotationRepository,
    private val prefs: AppPreferences,
) : ViewModel() {
    val items: StateFlow<List<LibraryItem>> = profiles.currentId.flatMapLatest { library.observeLibrary(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    private suspend fun build(ids: Set<String>): List<ExportBook> {
        val pid = profiles.currentIdNow()
        val meanings = annotations.observeMeanings(pid).first()
        return items.value.filter { it.book.id in ids }.map { item ->
            val book = item.book
            val chapters = if (book.format == "EPUB") withContext(Dispatchers.IO) {
                runCatching { EpubSession.open(library.fileFor(book)).use { s -> s.spineHrefs.indices.map { s.chapterLabel(it) } } }.getOrDefault(emptyList())
            } else emptyList()
            fun loc(position: Int) = if (book.format == "PDF") (position + 1).toString() else chapters.getOrNull(position) ?: "Chapter ${position + 1}"
            val notes = annotations.notesForBook(pid, book.id)
            val notesByHighlight = notes.filter { it.highlightId != null }.associateBy { it.highlightId }
            ExportBook(
                id = book.id, title = book.displayTitle,
                authors = book.displayAuthor?.split(Regex("\\s*(,|&)\\s*"))?.filter { it.isNotBlank() }.orEmpty(),
                year = book.year, publisher = book.publisher, format = book.format, tags = item.tags,
                highlights = annotations.highlightsFor(pid, book.id).map { h -> ExportHighlight(h.text, loc(h.position), meanings[HighlightColor.fromKey(h.colorKey)], notesByHighlight[h.id]?.body, h.createdAt) },
                notes = notes.filter { it.highlightId == null }.map { n -> ExportNote(n.title, n.body, n.position?.let { loc(it) }, n.modifiedAt) },
            )
        }
    }

    private suspend fun style(): CitationStyle = prefs.settings.first().citationStyle

    fun exportSingleFile(ids: Set<String>, format: String, uri: Uri) = viewModelScope.launch {
        busy.value = true
        message.value = runCatching {
            val books = build(ids)
            val citationStyle = style()
            val text = when (format) {
                "md" -> books.joinToString("\n\n---\n\n") { ResearchExport.markdown(it, citationStyle) }
                "bib" -> ResearchExport.bibtex(books)
                else -> ResearchExport.csv(books)
            }
            withContext(Dispatchers.IO) {
                (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write there")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
            "Exported ${books.size} book${if (books.size == 1) "" else "s"}."
        }.getOrElse { "Export failed: ${it.message}" }
        busy.value = false
    }

    fun exportObsidian(ids: Set<String>, tree: Uri) = viewModelScope.launch {
        busy.value = true
        message.value = runCatching {
            val books = build(ids)
            withContext(Dispatchers.IO) {
                val dir = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Can't open that folder")
                books.forEach { b ->
                    val name = ResearchExport.obsidianFileName(b)
                    val file = dir.findFile(name) ?: dir.createFile("text/markdown", name.removeSuffix(".md")) ?: throw IOException("Can't create $name")
                    (context.contentResolver.openOutputStream(file.uri, "wt") ?: throw IOException("Can't write $name")).use { it.write(ResearchExport.obsidian(b, style()).toByteArray(Charsets.UTF_8)) }
                }
            }
            "Wrote ${books.size} note${if (books.size == 1) "" else "s"} to your Obsidian folder."
        }.getOrElse { "Export failed: ${it.message}" }
        busy.value = false
    }
}

@Composable
fun ExportScreen(onBack: () -> Unit, vm: ExportViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val withNotes = items.filter { it.highlightCount + it.noteCount > 0 }
    var selected by remember(withNotes.map { it.book.id }) { mutableStateOf(withNotes.map { it.book.id }.toSet()) }
    var format by remember { mutableStateOf("md") }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri -> uri?.let { vm.exportSingleFile(selected, format, it) } }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.exportObsidian(selected, it) } }

    Column(Modifier.fillMaxSize()) {
        KarTopBar("Export research notes", onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Exports contain your highlights, notes and references. The books themselves are never included.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("md" to "Markdown", "bib" to "BibTeX", "obsidian" to "Obsidian vault", "csv" to "Spreadsheet (CSV)").forEach { (key, label) ->
                    FilterChip(selected = format == key, onClick = { format = key }, label = { Text(label) })
                }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Books (${selected.size} of ${withNotes.size})", fontWeight = FontWeight.SemiBold)
                if (withNotes.isEmpty()) Text("No books have highlights or notes yet.")
                withNotes.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                        Checkbox(checked = item.book.id in selected, onCheckedChange = { on -> selected = if (on) selected + item.book.id else selected - item.book.id })
                        Column(Modifier.weight(1f)) {
                            Text(item.book.displayTitle, fontWeight = FontWeight.SemiBold)
                            Text("${item.highlightCount} highlights · ${item.noteCount} notes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            GlossyButton(
                if (format == "obsidian") "Choose Obsidian folder…" else "Export…",
                onClick = {
                    when (format) {
                        "obsidian" -> treeLauncher.launch(null)
                        "md" -> fileLauncher.launch("KAR READER notes.md")
                        "bib" -> fileLauncher.launch("KAR READER references.bib")
                        else -> fileLauncher.launch("KAR READER highlights.csv")
                    }
                },
                icon = Icons.Filled.Upload, enabled = selected.isNotEmpty() && !busy,
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold) }
        }
    }
}

