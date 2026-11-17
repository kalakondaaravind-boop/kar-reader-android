package com.kar.reader.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kar.reader.core.domain.TranscriptText
import com.kar.reader.core.domain.WhisperLanguages
import kotlinx.coroutines.delay
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

private const val AUTOMATIC = ""

@Composable
fun LanguagePicker(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = if (value == AUTOMATIC) "Automatic: English, Hindi, Hinglish" else WhisperLanguages.all.firstOrNull { it.first == value }?.second ?: value
    Box {
        GhostButton("Language: $label  ▾", { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            DropdownMenuItem(text = { Text("Automatic: English, Hindi, Hinglish") }, onClick = { onChange(AUTOMATIC); open = false })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Other languages are written only when you choose them here", style = MaterialTheme.typography.labelSmall) }, onClick = {}, enabled = false)
            WhisperLanguages.all.forEach { (code, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onChange(code); open = false }) }
        }
    }
}

@Composable
private fun SpeakerCountPicker(value: Int, separate: Boolean, onSeparate: (Boolean) -> Unit, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = separate, onCheckedChange = onSeparate)
        Text("Tell speakers apart")
        if (separate) {
            Text("·  Speakers:", Modifier.padding(start = 6.dp))
            listOf(0 to "Auto", 2 to "2", 3 to "3", 4 to "4", 5 to "5", 6 to "6").forEach { (n, l) -> Chip(l, value == n) { onChange(n) } }
        }
    }
}

@Composable
fun TranscribeScreen(state: AppState, nav: Nav, jobs: TranscribeJobs) {
    var language by remember { mutableStateOf(AUTOMATIC) }
    var separate by remember { mutableStateOf(true) }
    var speakers by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<TranscriptMeta?>(null) }
    val all = state.data.transcripts.filter { it.deletedAt == 0L }.sortedByDescending { it.createdAt }

    fun importFiles(files: List<File>) {
        if (files.isEmpty()) return
        if (!SpeechPaths.available()) { state.toast("The speech models are missing. Reinstall KAR READER."); return }
        files.forEach { src ->
            val id = state.newId()
            val wav = "$id.wav"
            val title = src.nameWithoutExtension
            val meta = TranscriptMeta(id, title, System.currentTimeMillis(), 0, wav, language, status = "queued", separate = separate, speakers = speakers)
            state.update { it.copy(transcripts = it.transcripts + meta.copy(status = "working")) }
            // Convert off the UI thread, then hand over to the transcription queue.
            Thread({
                try {
                    val ms = AudioImport.toSpeechWav(src, File(Paths.recordings, wav), {}, { false })
                    state.update { d -> d.copy(transcripts = d.transcripts.map { if (it.id == id) it.copy(durationMs = ms) else it }) }
                    jobs.submit(meta.copy(durationMs = ms))
                } catch (e: Exception) {
                    File(Paths.recordings, wav).delete()
                    state.update { d -> d.copy(transcripts = d.transcripts.map { if (it.id == id) it.copy(status = "failed", error = e.message ?: "Couldn't read this audio file.") else it }) }
                }
            }, "audio-import").apply { isDaemon = true; start() }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Title("Transcribe", Modifier.weight(1f))
            GhostButton("Import audio…", { importFiles(pickFiles("Import audio to transcribe", "WAV or MP3 audio", "wav", "mp3", "aiff", "au")) })
            PrimaryButton("● Record", { nav.go(Screen.Recording) })
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Muted("Works offline on this computer. Automatic mode writes English, Hindi and Hinglish; choose any other language by hand. You can pick several audio files at once.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { LanguagePicker(language) { language = it } }
                SpeakerCountPicker(speakers, separate, { separate = it }) { speakers = it }
            }
        }
        if (!SpeechPaths.available()) Panel(Modifier.fillMaxWidth()) { Text("The speech models weren't found, so transcription can't run. Reinstall KAR READER.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (all.isEmpty()) Empty("No transcripts yet", "Record a conversation or import an audio file. Each voice gets its own label.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(all, key = { it.id }) { t ->
                Panel(Modifier.fillMaxWidth(), onClick = if (t.status == "done") ({ nav.go(Screen.Transcript(t.id)) }) else null) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted("${formatDateTime(t.createdAt)} · ${TranscriptText.clock(t.durationMs)}${if (t.languages.isNotBlank()) " · " + t.languages else ""}")
                            when (t.status) {
                                "working", "queued" -> {
                                    val active = jobs.activeId == t.id
                                    Text(if (active) (if (jobs.stage == Stage.SPEAKERS) "Telling voices apart…" else "Writing the words…") else if (t.status == "working") "Preparing audio…" else "Waiting in line", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    if (active) ProgressBar(jobs.progress, Modifier.width(320.dp))
                                }
                                "failed" -> Text(t.error.ifBlank { "Failed" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                else -> if (t.error.isNotBlank()) Muted(t.error)
                            }
                        }
                        when (t.status) {
                            "working", "queued" -> if (jobs.activeId == t.id || t.status == "queued") GhostButton("Cancel", { jobs.cancel(t.id) })
                            "failed" -> if (File(Paths.recordings, t.audio).isFile && t.durationMs > 0) PrimaryButton("Transcribe again", { jobs.submit(t) })
                        }
                        TextButton(onClick = { confirm = t }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    confirm?.let { t -> ConfirmDialog("Move to recycle bin?", "“${t.title}” and its audio move to the recycle bin. Nothing is deleted yet.", "Move to bin", onConfirm = { jobs.cancel(t.id); state.update { d -> d.copy(transcripts = d.transcripts.map { if (it.id == t.id) it.copy(deletedAt = System.currentTimeMillis()) else it }) } }, onDismiss = { confirm = null }) }
}

@Composable
fun RecordingScreen(state: AppState, nav: Nav, jobs: TranscribeJobs) {
    var language by remember { mutableStateOf(AUTOMATIC) }
    var separate by remember { mutableStateOf(true) }
    var speakers by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("Recording ${formatDateTime(System.currentTimeMillis())}") }
    val id = remember { state.newId() }
    val recorder = remember { MicRecorder(File(Paths.recordings, "$id.wav")) }
    var started by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (started) recorder.stop() } }
    LaunchedEffect(started) {
        while (started) { elapsed = recorder.elapsedMs; level = recorder.level; recorder.error?.let { state.toast(it); started = false }; delay(120) }
    }
    fun finish(transcribe: Boolean) {
        val ms = recorder.stop(); started = false
        if (ms < 1000) { File(Paths.recordings, "$id.wav").delete(); state.toast("That recording was too short to keep."); nav.go(Screen.Transcribe); return }
        val meta = TranscriptMeta(id, title.ifBlank { "Recording" }, System.currentTimeMillis(), ms, "$id.wav", language, separate = separate, speakers = speakers)
        if (transcribe && SpeechPaths.available()) jobs.submit(meta) else state.update { it.copy(transcripts = it.transcripts + meta.copy(status = "failed", error = "Saved. Press “Transcribe again” when you're ready.")) }
        nav.go(Screen.Transcribe)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (started) { recorder.stop(); started = false; File(Paths.recordings, "$id.wav").delete() }; nav.go(Screen.Transcribe) }) { Text("← Back") }
            Title("Record", Modifier.weight(1f))
        }
        Panel(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(TranscriptText.clock(elapsed), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((level * 4f).coerceIn(0.02f, 1f)).background(if (paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.secondary))
                }
                if (!started) {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LanguagePicker(language) { language = it }
                    SpeakerCountPicker(speakers, separate, { separate = it }) { speakers = it }
                    PrimaryButton("● Start recording", { recorder.start(); started = true }, enabled = SpeechPaths.available() || true)
                    Muted("Recording uses your default Windows microphone. Audio stays on this computer.")
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton(if (paused) "Resume" else "Pause", { paused = !paused; recorder.paused = paused })
                        PrimaryButton("Stop and transcribe", { finish(true) })
                        GhostButton("Stop, transcribe later", { finish(false) })
                    }
                }
            }
        }
    }
}

@Composable
fun TranscriptScreen(state: AppState, nav: Nav, id: String) {
    val meta = state.data.transcripts.firstOrNull { it.id == id }
    if (meta == null) { LaunchedEffect(Unit) { nav.go(Screen.Transcribe) }; return }
    val settings = state.data.settings
    var lines by remember(id) { mutableStateOf(TranscriptFiles.load(id)) }
    fun saveLines(l: List<Line>) { lines = l; TranscriptFiles.save(id, l) }
    var query by remember { mutableStateOf("") }
    var speakerFilter by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var merging by remember { mutableStateOf<Int?>(null) }
    var exportMenu by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(0L) }
    val clip = remember(id) { runCatching { AudioSystem.getClip().also { c -> AudioSystem.getAudioInputStream(File(Paths.recordings, meta.audio)).use { c.open(it) } } }.getOrNull() }
    DisposableEffect(id) { onDispose { runCatching { clip?.stop(); clip?.close() } } }
    LaunchedEffect(playing) { while (playing && clip != null) { nowMs = clip.microsecondPosition / 1000; if (!clip.isRunning) playing = false; delay(150) } }
    fun playFrom(ms: Long) { clip?.let { it.microsecondPosition = ms * 1000; it.start(); playing = true } }

    val speakersInUse = lines.map { it.speaker }.distinct().sorted()
    val shares = TranscriptText.talkShare(lines.map { TranscriptLine(it.startMs, it.endMs, it.speaker, it.text) })
    val shown = lines.withIndex().filter { (_, l) ->
        (speakerFilter == null || l.speaker == speakerFilter) &&
            (query.isBlank() || displayText(l.text, settings).contains(query, true) || speakerName(meta, l.speaker).contains(query, true))
    }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { nav.go(Screen.Transcribe) }) { Text("← Transcripts") }
            Column(Modifier.weight(1f)) {
                Text(meta.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted("${formatDateTime(meta.createdAt)} · ${TranscriptText.clock(meta.durationMs)} · ${meta.languages} · ${speakersInUse.size} speaker${if (speakersInUse.size == 1) "" else "s"}")
            }
            Chip("Devanagari", !settings.hindiRoman) { state.update { it.copy(settings = settings.copy(hindiRoman = false)) } }
            Chip("Roman", settings.hindiRoman) { state.update { it.copy(settings = settings.copy(hindiRoman = true)) } }
            Box {
                PrimaryButton("Export ▾", { exportMenu = true })
                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                    listOf("txt" to "Text (.txt)", "md" to "Markdown (.md)", "srt" to "Subtitles (.srt)", "vtt" to "Subtitles (.vtt)", "csv" to "Spreadsheet (.csv)", "json" to "Data (.json)").forEach { (ext, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            exportMenu = false
                            val f = saveFileDialog("Export transcript", "${slug(meta.title)}.$ext") ?: return@DropdownMenuItem
                            val body = when (ext) { "txt" -> ExportFormats.txt(meta, lines, settings); "md" -> ExportFormats.md(meta, lines, settings); "srt" -> ExportFormats.srt(meta, lines, settings); "vtt" -> ExportFormats.vtt(meta, lines, settings); "csv" -> ExportFormats.csv(meta, lines, settings); else -> ExportFormats.json(meta, lines, settings) }
                            File(if (f.name.contains('.')) f.path else f.path + "." + ext).writeText(body, Charsets.UTF_8)
                            state.toast("Saved ${f.name}")
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Save as a note") }, onClick = {
                        exportMenu = false
                        state.update { it.copy(notes = it.notes + Note(state.newId(), meta.bookId, meta.title, ExportFormats.md(meta, lines, settings), System.currentTimeMillis())) }
                        state.toast("Saved to Notes")
                    })
                    DropdownMenuItem(text = { Text("Copy all text") }, onClick = { exportMenu = false; copyToClipboard(ExportFormats.txt(meta, lines, settings)); state.toast("Copied") })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (clip != null) {
                PrimaryButton(if (playing) "❚❚ Pause" else "▶ Play", { if (playing) { clip.stop(); playing = false } else playFrom(clip.microsecondPosition / 1000) })
                Muted(TranscriptText.clock(nowMs))
            } else Muted("Audio playback isn't available for this recording.")
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.width(280.dp), placeholder = { Text("Search words or speakers") })
            Chip("Everyone", speakerFilter == null) { speakerFilter = null }
            speakersInUse.forEach { sp ->
                Chip("${speakerName(meta, sp)}  ${shares[sp] ?: 0}%", speakerFilter == sp) { speakerFilter = if (speakerFilter == sp) null else sp }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Muted("Click a line to play from it. Rename or merge speakers:")
            speakersInUse.forEach { sp ->
                TextButton(onClick = { renaming = sp }) { Text("Rename ${speakerName(meta, sp)}") }
                if (speakersInUse.size > 1) TextButton(onClick = { merging = sp }) { Text("Merge ${speakerName(meta, sp)} into…") }
            }
        }
        if (lines.isEmpty()) Empty("No speech found", meta.error.ifBlank { "This recording had no recognisable speech." })
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(shown, key = { _, v -> v.index }) { _, (index, l) ->
                val color = KarColors.SpeakerPalette[l.speaker % KarColors.SpeakerPalette.size]
                val active = playing && nowMs in l.startMs..l.endMs
                Panel(Modifier.fillMaxWidth(), onClick = { playFrom(l.startMs) }) {
                    Row(Modifier.padding(14.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(5.dp).fillMaxHeight().clip(CircleShape).background(color))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(speakerName(meta, l.speaker), color = color, style = MaterialTheme.typography.labelLarge)
                                Muted(TranscriptText.clock(l.startMs))
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { editing = index }) { Text("Edit") }
                                TextButton(onClick = { copyToClipboard(displayText(l.text, settings)) }) { Text("Copy") }
                            }
                            Text(displayText(l.text, settings), style = MaterialTheme.typography.bodyLarge, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
    editing?.let { i -> PromptDialog("Edit this line", lines[i].text, multiline = true, onDone = { t -> saveLines(lines.mapIndexed { k, l -> if (k == i) l.copy(text = t) else l }) }, onDismiss = { editing = null }) }
    renaming?.let { sp -> PromptDialog("Rename ${speakerName(meta, sp)}", meta.speakerNames[sp].orEmpty(), onDone = { n -> state.update { d -> d.copy(transcripts = d.transcripts.map { if (it.id == id) it.copy(speakerNames = it.speakerNames + (sp to n)) else it }) } }, onDismiss = { renaming = null }) }
    merging?.let { from ->
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("Merge ${speakerName(meta, from)} into…") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { speakersInUse.filter { it != from }.forEach { to -> GhostButton(speakerName(meta, to), { saveLines(lines.map { if (it.speaker == from) it.copy(speaker = to) else it }); merging = null }, Modifier.fillMaxWidth()) } } },
            confirmButton = {}, dismissButton = { TextButton(onClick = { merging = null }) { Text("Cancel") } },
        )
    }
}
