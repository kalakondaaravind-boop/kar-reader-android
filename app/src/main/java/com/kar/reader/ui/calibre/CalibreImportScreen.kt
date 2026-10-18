package com.kar.reader.ui.calibre

import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.EmptyState
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.data.files.ImportResult
import com.kar.reader.feature.calibre.CalibreImporter
import com.kar.reader.feature.calibre.CalibreLibrary
import com.kar.reader.feature.calibre.CalibreOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CalibreViewModel @Inject constructor(private val importer: CalibreImporter) : ViewModel() {
    val tree = MutableStateFlow<Uri?>(null)
    val library = MutableStateFlow<CalibreLibrary?>(null)
    val options = MutableStateFlow(CalibreOptions())
    val busy = MutableStateFlow<String?>(null)
    val result = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    private var job: Job? = null

    fun pick(uri: Uri) {
        tree.value = uri
        error.value = null
        result.value = null
        job = viewModelScope.launch {
            busy.value = "Reading metadata.db…"
            runCatching { importer.read(uri) }.onSuccess { library.value = it }.onFailure { error.value = it.message }
            busy.value = null
        }
    }

    fun import() {
        val uri = tree.value ?: return
        val lib = library.value ?: return
        job = viewModelScope.launch {
            val results = runCatching {
                importer.import(uri, lib.importable, options.value) { i, n, title -> busy.value = "Importing $i of $n: $title" }
            }.getOrElse { error.value = it.message; emptyList() }
            busy.value = null
            val ok = results.count { it is ImportResult.Imported }
            val dup = results.count { it is ImportResult.Duplicate }
            val failed = results.filterIsInstance<ImportResult.Failed>()
            result.value = "$ok imported · $dup already in your library" + if (failed.isNotEmpty()) " · ${failed.size} couldn't be read" else ""
        }
    }

    fun cancel() { job?.cancel(); busy.value = null }
}

@Composable
fun CalibreImportScreen(onBack: () -> Unit, vm: CalibreViewModel = hiltViewModel()) {
    val lib by vm.library.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::pick) }

    Column(Modifier.fillMaxSize()) {
        KarTopBar("Import from Calibre", onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("Choose your “Calibre Library” folder (the one containing metadata.db). It's only read, never changed. Titles, authors, tags, series, ratings and covers come across; EPUB is preferred over PDF when a book has both.", fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    GlossyButton(if (lib == null) "Choose folder…" else "Choose another folder…", onClick = { picker.launch(null) }, icon = Icons.Filled.Folder, kind = if (lib == null) GlossyKind.PRIMARY else GlossyKind.PEARL, enabled = busy == null)
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            val l = lib
            if (l != null) {
                item {
                    Text("${l.books.size} books · ${l.authorCount} authors · ${l.importable.size} can be imported", fontWeight = FontWeight.SemiBold)
                    if (l.skipped.isNotEmpty()) Text("${l.skipped.size} books have no PDF or EPUB and will be skipped.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = options.keepTags, onClick = { vm.options.value = options.copy(keepTags = !options.keepTags) }, label = { Text("Keep tags") })
                        FilterChip(selected = options.keepSeries, onClick = { vm.options.value = options.copy(keepSeries = !options.keepSeries) }, label = { Text("Keep series") })
                        FilterChip(selected = options.keepRatings, onClick = { vm.options.value = options.copy(keepRatings = !options.keepRatings) }, label = { Text("Keep ratings") })
                        FilterChip(selected = options.importCovers, onClick = { vm.options.value = options.copy(importCovers = !options.importCovers) }, label = { Text("Import covers") })
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlossyButton("Import ${l.importable.size} books", onClick = vm::import, enabled = busy == null && l.importable.isNotEmpty())
                    }
                }
                items(l.books.take(300), key = { it.id }) { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(b.authors.ifBlank { null }, b.series?.let { s -> "$s${b.seriesIndex?.let { " #${it.toInt()}" } ?: ""}" }, b.tags.takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString(" · "),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(b.format ?: "skipped", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (b.format == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
                if (l.books.size > 300) item { Text("…and ${l.books.size - 300} more", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (busy == null && error == null) {
                item { EmptyState("No Calibre library chosen", "Copy your Calibre Library folder to this device (for example with a USB cable), then choose it here.") }
            }
            busy?.let { item { Column { Text(it); Spacer(Modifier.height(6.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } } }
            result?.let { item { Text(it, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold) } }
        }
    }
}
