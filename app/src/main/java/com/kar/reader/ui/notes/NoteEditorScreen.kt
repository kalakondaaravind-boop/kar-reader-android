@file:OptIn(ExperimentalMaterial3Api::class)

package com.kar.reader.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.ConfirmDialog
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.data.db.NoteEntity
import com.kar.reader.data.db.NoteVersionEntity
import com.kar.reader.data.repo.AnnotationRepository
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val annotations: AnnotationRepository,
) : ViewModel() {
    val noteId: String = checkNotNull(savedState.get<String>("noteId"))
    val note: StateFlow<NoteEntity?> = annotations.observeNote(noteId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val versions: StateFlow<List<NoteVersionEntity>> = annotations.observeVersions(noteId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun initial(): NoteEntity = note.filterNotNull().first()

    /** Saves only when the text changed; the previous text goes into history automatically. */
    fun save(title: String, body: String) = viewModelScope.launch {
        val current = note.value ?: return@launch
        if (current.title != title || current.body != body) annotations.saveNote(profiles.currentIdNow(), current.id, title, body)
    }

    fun restore(v: NoteVersionEntity) = viewModelScope.launch { annotations.restoreVersion(v) }

    fun delete(onDone: () -> Unit) = viewModelScope.launch { annotations.deleteNote(noteId); onDone() }
}

@Composable
fun NoteEditorScreen(onBack: () -> Unit, vm: NoteEditorViewModel = hiltViewModel()) {
    val note by vm.note.collectAsStateWithLifecycle()
    val versions by vm.versions.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf<String?>(null) }
    var body by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(Unit) {
        val n = vm.initial()
        title = n.title; body = n.body
    }
    // After a restore, show the restored text.
    LaunchedEffect(note?.modifiedAt) {
        val n = note ?: return@LaunchedEffect
        if (title != null && (n.title != title || n.body != body) && showHistory.not()) { title = n.title; body = n.body }
    }
    val saveNow = { val t = title; val b = body; if (t != null && b != null) vm.save(t, b) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) saveNow() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); saveNow() }
    }
    BackHandler { saveNow(); onBack() }

    Column(Modifier.fillMaxSize().imePadding()) {
        KarTopBar(
            title = title?.ifBlank { "Untitled note" } ?: "Note",
            subtitle = note?.let { "Edited ${Format.relative(it.modifiedAt)} · ${versions.size} earlier version${if (versions.size == 1) "" else "s"}" },
            onBack = { saveNow(); onBack() }, backIcon = Icons.AutoMirrored.Filled.ArrowBack,
            actions = {
                IconButton(onClick = { saveNow(); showHistory = true }) { Icon(Icons.Filled.History, "Note history") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Move note to recycle bin") }
            },
        )
        if (title != null && body != null) {
            Column(Modifier.padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title!!, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body!!, { body = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().weight(1f))
                GlossyButton("Save", onClick = saveNow, kind = GlossyKind.PRIMARY)
            }
        }
    }

    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(520.dp)) {
                item {
                    Text("Note history", style = MaterialTheme.typography.headlineSmall)
                    Text("Every save keeps the previous text. Restoring saves the current text as a version too, so nothing is lost.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (versions.isEmpty()) item { Text("No earlier versions yet.") }
                items(versions, key = { it.id }) { v ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(
                            "${Format.date(v.savedAt)} · ${when (v.origin) { "SYNC" -> "from another device"; "RESTORE" -> "before a restore"; else -> "saved here" }}",
                            fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        )
                        if (v.title.isNotBlank()) Text(v.title, fontWeight = FontWeight.SemiBold)
                        Text(v.body, fontFamily = FontFamily.Serif, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        GlossyButton("Restore this version", onClick = {
                            vm.restore(v); title = v.title; body = v.body; showHistory = false
                        }, kind = GlossyKind.GOLD, compact = true)
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Move note to recycle bin?", "You can restore it from Settings › Recycle bin for 30 days.", "Move to bin",
            onConfirm = { vm.delete(onBack) }, onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
}
