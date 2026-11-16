@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kar.reader.ui.transcribe

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.EmptyState
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.GlossyProgress
import com.kar.reader.core.designsystem.KarColors
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.core.designsystem.SectionLabel
import com.kar.reader.core.domain.TranscriptText
import com.kar.reader.core.domain.WhisperLanguages
import com.kar.reader.data.db.TranscriptEntity
import com.kar.reader.data.db.TranscriptStatus
import com.kar.reader.data.db.displayTitle
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.repo.LibraryRepository
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.data.repo.TranscriptRepository
import com.kar.reader.feature.transcribe.NewTranscriptOptions
import com.kar.reader.feature.transcribe.ProcessingState
import com.kar.reader.feature.transcribe.RecordingState
import com.kar.reader.feature.transcribe.TranscriptionManager
import com.kar.reader.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class TranscriptsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    library: LibraryRepository,
    repo: TranscriptRepository,
    private val manager: TranscriptionManager,
    private val prefs: AppPreferences,
) : ViewModel() {
    val hindiRoman: StateFlow<Boolean> = prefs.settings.map { it.hindiRoman }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setHindiRoman(on: Boolean) = viewModelScope.launch { prefs.setHindiRoman(on) }

    val transcripts: StateFlow<List<TranscriptEntity>?> = profiles.currentId.flatMapLatest { repo.observeList(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val books: StateFlow<List<Pair<String, String>>> = profiles.currentId.flatMapLatest { library.observeLibrary(it) }
        .map { items -> items.map { it.book.id to it.book.displayTitle }.sortedBy { it.second.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recording: StateFlow<RecordingState?> = manager.recording
    val processing: StateFlow<ProcessingState?> = manager.processing
    val modelsAvailable: Boolean get() = manager.modelsAvailable

    val options = MutableStateFlow(NewTranscriptOptions(title = "", language = "", separateSpeakers = true, expectedSpeakers = 0, bookId = null))
    val message = MutableStateFlow<String?>(null)

    fun update(change: (NewTranscriptOptions) -> NewTranscriptOptions) = options.update(change)

    private fun defaultTitle() = "Recording " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())

    fun record(onStarted: (String) -> Unit) = viewModelScope.launch {
        val o = options.value
        runCatching { manager.startRecording(profiles.currentIdNow(), o.copy(title = o.title.trim().ifEmpty { defaultTitle() })) }
            .onSuccess { options.update { it.copy(title = "") }; onStarted(it) }
            .onFailure { message.value = it.message ?: "Couldn't start recording." }
    }

    fun import(uri: Uri, onQueued: (String) -> Unit) = viewModelScope.launch {
        runCatching { manager.importAudio(profiles.currentIdNow(), uri, options.value.copy(title = options.value.title.trim())) }
            .onSuccess { options.update { it.copy(title = "") }; onQueued(it) }
            .onFailure { message.value = it.message ?: "Couldn't import that file." }
    }
}

@Composable
fun TranscriptsScreen(
    onBack: () -> Unit,
    onOpenTranscript: (String) -> Unit,
    onOpenRecording: () -> Unit,
    vm: TranscriptsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val list by vm.transcripts.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val processing by vm.processing.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val hindiRoman by vm.hindiRoman.collectAsStateWithLifecycle()
    var permissionNote by remember { mutableStateOf<String?>(null) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (mic) { permissionNote = null; vm.record { onOpenRecording() } }
        else permissionNote = "KAR READER needs the microphone to record. You can allow it in Android Settings › Apps › KAR READER › Permissions."
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(it, onOpenTranscript) } }

    fun startRecording() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) vm.record { onOpenRecording() } else permissions.launch(needed.toTypedArray())
    }

    Column(Modifier.fillMaxSize()) {
        KarTopBar("Transcribe", subtitle = "On this device · tells speakers apart", onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!vm.modelsAvailable) item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Text("This build doesn't include the speech models, so recordings can be saved but not transcribed.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            recording?.let { r ->
                item {
                    GlassCard(Modifier.fillMaxWidth(), onClick = onOpenRecording) {
                        Text("Recording in progress", fontWeight = FontWeight.SemiBold, color = KarColors.Red)
                        Text("${r.title} · ${TranscriptText.clock(r.elapsedMs)}${if (r.paused) " · paused" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        GlossyButton("Open recorder", onClick = onOpenRecording, icon = Icons.Filled.Mic, compact = true)
                    }
                }
            }
            if (recording == null) item {
                Text("Turn a lecture, interview or meeting into text, with each person's words labelled.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlossyButton("Record now", onClick = ::startRecording, kind = GlossyKind.GOLD, icon = Icons.Filled.Mic, modifier = Modifier.weight(1f))
                    GlossyButton(
                        "Import audio",
                        onClick = { picker.launch(arrayOf("audio/*", "video/mp4", "application/ogg")) },
                        kind = GlossyKind.PEARL, icon = Icons.Filled.UploadFile, modifier = Modifier.weight(1f),
                    )
                }
                Text("MP3, M4A, WAV, OGG, FLAC, AMR and the audio of MP4 videos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            (permissionNote ?: message)?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.error) } }
            if (recording == null) item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TitleField(options.title, { t -> vm.update { it.copy(title = t) } }, "Named after the date if left empty")
                        TranscribeOptionsFields(
                            language = options.language, onLanguage = { l -> vm.update { it.copy(language = l) } },
                            separateSpeakers = options.separateSpeakers, onSeparate = { v -> vm.update { it.copy(separateSpeakers = v) } },
                            expectedSpeakers = options.expectedSpeakers, onExpected = { n -> vm.update { it.copy(expectedSpeakers = n) } },
                        )
                        HindiScriptPicker(hindiRoman, vm::setHindiRoman)
                        BookPicker(books, options.bookId) { id -> vm.update { it.copy(bookId = id) } }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = KarColors.Green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Everything runs on this device. Audio and text never leave your phone, and deleted transcripts wait 30 days in the recycle bin.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val items = list
            if (items != null && items.isNotEmpty()) item { SectionLabel("Your transcripts", Modifier.padding(top = 8.dp)) }
            if (items != null && items.isEmpty()) item {
                EmptyState("No transcripts yet", "Record or import audio to get a transcript with speakers labelled.")
            }
            items(items.orEmpty(), key = { it.id }) { t -> TranscriptRow(t, processing, onClick = { if (t.status == TranscriptStatus.RECORDING) onOpenRecording() else onOpenTranscript(t.id) }) }
        }
    }
}

@Composable
private fun TranscriptRow(t: TranscriptEntity, processing: ProcessingState?, onClick: () -> Unit) {
    val live = processing?.takeIf { it.transcriptId == t.id }
    val status = live?.status ?: t.status
    GlassCard(Modifier.fillMaxWidth().widthIn(max = 900.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        Format.relative(t.createdAt),
                        t.durationMs.takeIf { it > 0 }?.let { TranscriptText.clock(it) },
                        t.speakerCount.takeIf { it > 0 && t.status == TranscriptStatus.DONE }?.let { if (it == 1) "1 speaker" else "$it speakers" },
                        t.detectedLanguages.takeIf { it.isNotBlank() }?.let { WhisperLanguages.describe(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            StatusChip(status)
        }
        if (status in TranscriptStatus.working) {
            Spacer(Modifier.height(10.dp))
            GlossyProgress(live?.progress ?: t.progress, Modifier.fillMaxWidth(), height = 6.dp)
        }
        t.error?.takeIf { status == TranscriptStatus.FAILED || status == TranscriptStatus.CANCELLED }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (status == TranscriptStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
