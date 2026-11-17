package com.kar.reader.ui.transcribe

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.ConfirmDialog
import com.kar.reader.core.designsystem.EmptyState
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.GlossyProgress
import com.kar.reader.core.designsystem.KarColors
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.core.designsystem.LocalKarStyle
import com.kar.reader.core.domain.TranscriptLine
import com.kar.reader.core.domain.TranscriptText
import com.kar.reader.core.domain.WhisperLanguages
import com.kar.reader.data.db.TranscriptEntity
import com.kar.reader.data.db.TranscriptMarkEntity
import com.kar.reader.data.db.TranscriptSegmentEntity
import com.kar.reader.data.db.TranscriptSpeakerEntity
import com.kar.reader.data.db.TranscriptStatus
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.core.domain.HindiRoman
import kotlinx.coroutines.flow.map
import com.kar.reader.data.repo.TranscriptExport
import com.kar.reader.data.repo.TranscriptRepository
import com.kar.reader.feature.transcribe.ProcessingState
import com.kar.reader.feature.transcribe.TranscriptionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Plays the transcript's recording; position is polled while playing. */
class TranscriptPlayer {
    private var player: MediaPlayer? = null
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()
    var durationMs: Long = 0; private set
    private var file: File? = null

    fun prepare(f: File): Boolean {
        if (file == f && player != null) return true
        release()
        if (!f.isFile) return false
        return runCatching {
            player = MediaPlayer().apply {
                setDataSource(f.path)
                prepare()
                setOnCompletionListener { _playing.value = false }
            }
            durationMs = player!!.duration.toLong()
            file = f
            true
        }.getOrDefault(false)
    }

    fun toggle() { val p = player ?: return; if (p.isPlaying) { p.pause(); _playing.value = false } else { p.start(); _playing.value = true } }

    fun playFrom(ms: Long) { val p = player ?: return; p.seekTo(ms.toInt()); _position.value = ms; if (!p.isPlaying) { p.start(); _playing.value = true } }

    fun seek(ms: Long) { player?.seekTo(ms.toInt()); _position.value = ms }

    fun cycleSpeed() {
        val next = when (_speed.value) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; 2f -> 0.75f; else -> 1f }
        val p = player ?: return
        runCatching {
            val wasPlaying = p.isPlaying
            p.playbackParams = p.playbackParams.setSpeed(next)
            if (!wasPlaying) p.pause() // setting params starts playback on some versions
            _speed.value = next
        }
    }

    fun poll() { player?.let { if (it.isPlaying) _position.value = it.currentPosition.toLong() } }

    fun release() { runCatching { player?.release() }; player = null; file = null; _playing.value = false }
}

@HiltViewModel
class TranscriptViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: TranscriptRepository,
    private val manager: TranscriptionManager,
    private val profiles: ProfileRepository,
    private val prefs: AppPreferences,
) : ViewModel() {
    val hindiRoman: StateFlow<Boolean> = prefs.settings.map { it.hindiRoman }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setHindiRoman(on: Boolean) = viewModelScope.launch { prefs.setHindiRoman(on) }
    val id: String = checkNotNull(saved["transcriptId"])
    val transcript: StateFlow<TranscriptEntity?> = repo.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val segments: StateFlow<List<TranscriptSegmentEntity>> = repo.observeSegments(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val speakers: StateFlow<List<TranscriptSpeakerEntity>> = repo.observeSpeakers(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val marks: StateFlow<List<TranscriptMarkEntity>> = repo.observeMarks(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val processing: StateFlow<ProcessingState?> = manager.processing
    val player = TranscriptPlayer()
    val message = MutableStateFlow<String?>(null)
    private var poller: Job? = null

    fun preparePlayer(t: TranscriptEntity) {
        if (player.prepare(repo.audioFile(t)) && poller == null) {
            poller = viewModelScope.launch { while (isActive) { player.poll(); delay(200) } }
        }
    }

    fun rename(title: String) = viewModelScope.launch { repo.rename(id, title) }
    fun renameSpeaker(speaker: Int, name: String) = viewModelScope.launch { repo.renameSpeaker(id, speaker, name) }
    fun merge(from: Int, into: Int) = viewModelScope.launch { repo.mergeSpeakers(id, from, into) }
    fun editSegment(segmentId: String, text: String, speaker: Int) = viewModelScope.launch {
        repo.editSegment(segmentId, text); repo.setSegmentSpeaker(segmentId, speaker)
    }
    fun cancel() = manager.cancel(id)
    fun rerun(language: String, separate: Boolean, expected: Int) = viewModelScope.launch {
        manager.updateOptions(id, language, separate, expected); manager.transcribe(id)
    }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { player.release(); repo.delete(id); onDone() }
    fun saveAsNote(onSaved: (String) -> Unit) = viewModelScope.launch {
        runCatching { repo.saveAsNote(profiles.currentIdNow(), id) }
            .onSuccess { message.value = "Saved as a note."; onSaved(it.id) }
            .onFailure { message.value = "Couldn't save the note: ${it.message}" }
    }
    fun export(format: TranscriptExport, uri: Uri) = viewModelScope.launch {
        message.value = runCatching { repo.export(id, format, uri); "Exported as ${format.label}." }.getOrElse { "Export failed: ${it.message}" }
    }

    override fun onCleared() { player.release() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit, vm: TranscriptViewModel = hiltViewModel()) {
    val t by vm.transcript.collectAsStateWithLifecycle()
    val segments by vm.segments.collectAsStateWithLifecycle()
    val speakers by vm.speakers.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val processing by vm.processing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val position by vm.player.position.collectAsStateWithLifecycle()
    val playing by vm.player.playing.collectAsStateWithLifecycle()
    val speed by vm.player.speed.collectAsStateWithLifecycle()
    val hindiRoman by vm.hindiRoman.collectAsStateWithLifecycle()

    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var rerun by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TranscriptSegmentEntity?>(null) }
    var speakerSheet by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<TranscriptExport?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = pendingExport; if (uri != null && f != null) vm.export(f, uri); pendingExport = null
    }

    val transcript = t
    DisposableEffect(Unit) { onDispose { vm.player.release() } }
    LaunchedEffect(transcript?.status, transcript?.durationMs) {
        if (transcript != null && transcript.status != TranscriptStatus.RECORDING && transcript.durationMs > 0) vm.preparePlayer(transcript)
    }

    val names = speakers.associate { it.speaker to it.name }
    fun name(s: Int) = names[s] ?: "Speaker ${s + 1}"
    val done = transcript?.status == TranscriptStatus.DONE
    val lines = segments.map { TranscriptLine(it.startMs, it.endMs, it.speaker, it.text) }
    val hasHindi = segments.any { HindiRoman.hasDevanagari(it.text) }
    val share = TranscriptText.talkShare(lines)
    val speakerIds = (speakers.map { it.speaker } + segments.map { it.speaker }).distinct().sorted()

    Column(Modifier.fillMaxSize()) {
        KarTopBar(
            transcript?.title ?: "Transcript",
            subtitle = transcript?.let { subtitle(it) },
            onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack,
        ) {
            if (done && segments.any { HindiRoman.hasDevanagari(it.text) }) {
                TextButton(onClick = { vm.setHindiRoman(!hindiRoman) }) { Text(if (hindiRoman) "देवनागरी" else "Roman") }
            }
            if (done) IconButton(onClick = { speakerSheet = true }) { Icon(Icons.Filled.People, contentDescription = "Speakers") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; renaming = true })
                if (done && hasHindi) {
                    DropdownMenuItem(text = { Text(if (hindiRoman) "Show Hindi in Devanagari" else "Show Hindi in Roman letters") }, onClick = { menu = false; vm.setHindiRoman(!hindiRoman) })
                }
                if (done) {
                    DropdownMenuItem(text = { Text("Save as note") }, leadingIcon = { Icon(Icons.Filled.EditNote, null) }, onClick = { menu = false; vm.saveAsNote(onOpenNote) })
                    TranscriptExport.entries.forEach { f ->
                        DropdownMenuItem(text = { Text("Export · ${f.label}") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = {
                            menu = false; pendingExport = f
                            exporter.launch(safeName(transcript?.title ?: "transcript") + "." + f.extension)
                        })
                    }
                }
                if (transcript != null && transcript.status !in TranscriptStatus.working && transcript.status != TranscriptStatus.RECORDING && transcript.durationMs > 0) {
                    DropdownMenuItem(text = { Text(if (done) "Transcribe again…" else "Transcribe…") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = { menu = false; rerun = true })
                }
                DropdownMenuItem(text = { Text("Move to recycle bin") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; confirmDelete = true })
            }
        }
        if (transcript == null) return@Column

        message?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary) }

        val live = processing?.takeIf { it.transcriptId == transcript.id }
        when {
            transcript.status in TranscriptStatus.working || live != null ->
                ProcessingPanel(transcript, live, onCancel = vm::cancel)
            transcript.status != TranscriptStatus.DONE ->
                NotReadyPanel(transcript, onTranscribe = { rerun = true })
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth > 840.dp
                Column(Modifier.fillMaxSize()) {
                    PlayerBar(position, vm.player.durationMs.coerceAtLeast(transcript.durationMs), playing, speed, segments, marks, onToggle = vm.player::toggle, onSeek = vm.player::seek, onSpeed = vm.player::cycleSpeed)
                    Row(Modifier.fillMaxSize()) {
                        LazyColumn(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (segments.isEmpty()) item { EmptyState("No speech found", "The recording may be silent or too noisy. Try “Transcribe again” with the language set explicitly.") }
                            items(segments, key = { it.id }) { s ->
                                val current = position in s.startMs until s.endMs && (playing || position > 0)
                                val shown = if (hindiRoman) s.copy(text = HindiRoman.romanize(s.text)) else s
                                SegmentRow(shown, name(s.speaker), current, onClick = { vm.player.playFrom(s.startMs) }, onLongClick = { editing = shown })
                            }
                            item {
                                Text(
                                    "Tap a line to play it. Touch and hold to correct the text or the speaker. Transcribed on this device with ${WhisperLanguages.describe(transcript.detectedLanguages).ifBlank { "Whisper" }} detected.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp),
                                )
                            }
                        }
                        if (wide) {
                            Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                                SpeakersPanel(speakerIds, ::name, share, onRename = vm::renameSpeaker, onMerge = vm::merge)
                            }
                        }
                    }
                }
            }
        }
    }

    if (speakerSheet) {
        AlertDialog(
            onDismissRequest = { speakerSheet = false },
            confirmButton = { TextButton(onClick = { speakerSheet = false }) { Text("Done") } },
            title = { Text("Speakers") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { SpeakersPanel(speakerIds, ::name, share, onRename = vm::renameSpeaker, onMerge = vm::merge) } },
        )
    }
    if (renaming && transcript != null) {
        var text by remember { mutableStateOf(transcript.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(text, { text = it.take(120) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.rename(text); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Move to recycle bin?", "The transcript and its recording stay in the recycle bin for 30 days, then are deleted.", "Move to bin",
            onConfirm = { confirmDelete = false; vm.delete(onBack) }, onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
    if (rerun && transcript != null) {
        var language by remember { mutableStateOf(transcript.languageChoice) }
        var separate by remember { mutableStateOf(transcript.separateSpeakers) }
        var expected by remember { mutableStateOf(transcript.expectedSpeakers) }
        AlertDialog(
            onDismissRequest = { rerun = false },
            title = { Text(if (done) "Transcribe again" else "Transcribe") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (done) Text("The current text is replaced. Speaker names you gave are kept by number; hand corrections are not.", style = MaterialTheme.typography.bodySmall)
                    TranscribeOptionsFields(language, { language = it }, separate, { separate = it }, expected, { expected = it })
                }
            },
            confirmButton = { TextButton(onClick = { rerun = false; vm.rerun(language, separate, expected) }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { rerun = false }) { Text("Cancel") } },
        )
    }
    editing?.let { seg ->
        var text by remember(seg.id) { mutableStateOf(seg.text) }
        var speaker by remember(seg.id) { mutableStateOf(seg.speaker) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Correct this line") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    Spacer(Modifier.height(12.dp))
                    Text("Who said it?", fontWeight = FontWeight.SemiBold)
                    speakerIds.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = speaker == s, onClick = { speaker = s }, role = Role.RadioButton).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = speaker == s, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            SpeakerDot(s, name(s), 26)
                            Spacer(Modifier.width(8.dp))
                            Text(name(s))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.editSegment(seg.id, text, speaker); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

private fun subtitle(t: TranscriptEntity): String = listOfNotNull(
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(t.createdAt)),
    t.durationMs.takeIf { it > 0 }?.let { TranscriptText.clock(it) },
    t.detectedLanguages.takeIf { it.isNotBlank() }?.let { WhisperLanguages.describe(it) },
).joinToString(" · ")

private fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|\\n]"), " ").trim().take(60).ifEmpty { "transcript" }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentRow(s: TranscriptSegmentEntity, name: String, current: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val eink = LocalKarStyle.current.eink
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth()
            .then(if (current) Modifier.background(if (eink) Color.White else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape).border(if (eink) 2.dp else 1.dp, if (eink) Color.Black else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = "Play from here", onLongClickLabel = "Correct this line")
            .padding(12.dp),
    ) {
        SpeakerDot(s.speaker, name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = speakerColor(s.speaker))
                Spacer(Modifier.width(10.dp))
                Text(TranscriptText.clock(s.startMs), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.edited) { Spacer(Modifier.width(8.dp)); Text("edited", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(s.text, fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 26.sp)
        }
    }
}

@Composable
private fun PlayerBar(
    position: Long, duration: Long, playing: Boolean, speed: Float,
    segments: List<TranscriptSegmentEntity>, marks: List<TranscriptMarkEntity>,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onSpeed: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
            }
            Spacer(Modifier.width(10.dp))
            Text(TranscriptText.clock(position), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Slider(
                value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { onSeek((it * duration).toLong()) },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(TranscriptText.clock(duration), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            TextButton(onClick = onSpeed) { Text("${if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()}×") }
        }
        // Who spoke when, along the timeline.
        if (duration > 0 && segments.isNotEmpty()) {
            SpeakerTimeline(segments, marks, duration, Modifier.fillMaxWidth().height(10.dp).padding(start = 58.dp, end = 64.dp))
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SpeakerTimeline(segments: List<TranscriptSegmentEntity>, marks: List<TranscriptMarkEntity>, duration: Long, modifier: Modifier) {
    val colors = segments.map { speakerColor(it.speaker) }
    val markColor = if (LocalKarStyle.current.eink) Color.Black else KarColors.Accent
    androidx.compose.foundation.Canvas(modifier) {
        drawRoundRect(Color.Gray.copy(alpha = 0.2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f))
        segments.forEachIndexed { i, s ->
            val x0 = size.width * s.startMs / duration; val x1 = size.width * s.endMs / duration
            drawRect(colors[i], topLeft = androidx.compose.ui.geometry.Offset(x0, 0f), size = androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(1f), size.height))
        }
        marks.forEach { m ->
            val x = size.width * m.atMs / duration
            drawRect(markColor, topLeft = androidx.compose.ui.geometry.Offset(x - 1.5f, -2f), size = androidx.compose.ui.geometry.Size(3f, size.height + 4f))
        }
    }
}

@Composable
private fun SpeakersPanel(ids: List<Int>, name: (Int) -> String, share: Map<Int, Int>, onRename: (Int, String) -> Unit, onMerge: (Int, Int) -> Unit) {
    var renaming by remember { mutableStateOf<Int?>(null) }
    var merging by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${ids.size} ${if (ids.size == 1) "voice" else "voices"} found. Tap a name to rename it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ids.forEach { s ->
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), onClick = { renaming = s }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeakerDot(s, name(s), 32)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name(s), fontWeight = FontWeight.SemiBold)
                        if (name(s) != "Speaker ${s + 1}") Text("Speaker ${s + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${share[s] ?: 0}%", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                GlossyProgress((share[s] ?: 0) / 100f, Modifier.fillMaxWidth(), height = 6.dp, color = speakerColor(s))
                if (ids.size > 1) TextButton(onClick = { merging = s }) { Text("Same person as…") }
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = KarColors.Success, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Voices are compared on this device only. Nothing about them is stored except these labels.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    renaming?.let { s ->
        var text by remember(s) { mutableStateOf(name(s)) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name for Speaker ${s + 1}") },
            text = { OutlinedTextField(text, { text = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { onRename(s, text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    merging?.let { from ->
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("${name(from)} is the same person as…") },
            text = {
                Column {
                    Text("Their lines are combined under one name. Use this when the app split one voice in two.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    ids.filter { it != from }.forEach { into ->
                        Row(Modifier.fillMaxWidth().selectable(selected = false, onClick = { onMerge(from, into); merging = null }, role = Role.Button).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SpeakerDot(into, name(into), 28); Spacer(Modifier.width(10.dp)); Text(name(into))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { merging = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProcessingPanel(t: TranscriptEntity, live: ProcessingState?, onCancel: () -> Unit) {
    val status = live?.status ?: t.status
    val progress = live?.progress ?: t.progress
    val steps = buildList {
        if (t.source == "IMPORTED") add(TranscriptStatus.CONVERTING to "Reading the audio")
        if (t.separateSpeakers) add(TranscriptStatus.SPEAKERS to "Telling voices apart")
        add(TranscriptStatus.WORDS to "Writing the words")
    }
    val currentIndex = steps.indexOfFirst { it.first == status }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Text(if (status == TranscriptStatus.QUEUED) "Waiting to start…" else "Transcribing on this device", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Spacer(Modifier.height(12.dp))
            steps.forEachIndexed { i, (_, label) ->
                val state = when { currentIndex < 0 -> 0; i < currentIndex -> 2; i == currentIndex -> 1; else -> 0 }
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (state) { 2 -> "✓"; 1 -> "${i + 1}"; else -> "${i + 1}" },
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.size(28.dp).background(
                            when (state) { 2 -> KarColors.Success; 1 -> KarColors.Primary; else -> Color(0xFFB4C0D3) }, RoundedCornerShape(14.dp),
                        ).padding(top = 5.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, fontWeight = if (state == 1) FontWeight.Bold else FontWeight.Normal)
                        if (state == 1) { Spacer(Modifier.height(4.dp)); GlossyProgress(progress, Modifier.fillMaxWidth(), height = 8.dp) }
                    }
                    if (state == 1) { Spacer(Modifier.width(10.dp)); Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        Text(
            "This can take a while — on many phones about as long as the recording itself. You can leave the app; a notification shows progress.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GlossyButton("Cancel (the recording is kept)", onClick = onCancel, kind = GlossyKind.DANGER, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun NotReadyPanel(t: TranscriptEntity, onTranscribe: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Text(statusLabel(t.status), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = if (t.status == TranscriptStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            t.error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (t.durationMs > 0) {
                Spacer(Modifier.height(6.dp))
                Text("Recording saved · ${TranscriptText.clock(t.durationMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                GlossyButton("Transcribe…", onClick = onTranscribe, kind = GlossyKind.ACCENT, icon = Icons.Filled.Refresh)
            }
        }
    }
}
