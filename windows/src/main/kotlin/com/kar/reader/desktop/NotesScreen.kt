package com.kar.reader.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kar.reader.core.domain.CitationStyle
import com.kar.reader.core.domain.Citations
import com.kar.reader.core.domain.ResearchExport
import com.kar.reader.core.model.HighlightColor
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun NotesScreen(state: AppState, nav: Nav) {
    var tab by remember { mutableStateOf("highlights") }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Title("Notes & highlights", Modifier.weight(1f))
            Chip("Highlights", tab == "highlights") { tab = "highlights" }
            Chip("Notes", tab == "notes") { tab = "notes" }
            GhostButton("Export every book (Markdown)…", {
                val dir = pickFolder("Choose a folder for the Markdown files") ?: return@GhostButton
                val style = CitationStyle.entries.firstOrNull { it.name == state.data.settings.citation } ?: CitationStyle.APA
                var n = 0
                state.liveBooks.filter { b -> state.data.highlights.any { it.bookId == b.id } || state.data.notes.any { it.bookId == b.id } }.forEach { b ->
                    File(dir, "${slug(b.title)}.md").writeText(ResearchExport.markdown(exportBookFor(state, b), style), Charsets.UTF_8); n++
                }
                state.toast(if (n == 0) "Nothing to export yet." else "Saved $n file${if (n == 1) "" else "s"} to ${dir.name}")
            })
        }
        if (tab == "highlights") AllHighlights(state, nav) else FreeNotes(state)
    }
}

@Composable
private fun AllHighlights(state: AppState, nav: Nav) {
    var query by remember { mutableStateOf("") }
    var color by remember { mutableStateOf<String?>(null) }
    var bookFilter by remember { mutableStateOf<String?>(null) }
    var carding by remember { mutableStateOf<Highlight?>(null) }
    var deleting by remember { mutableStateOf<Highlight?>(null) }
    var editing by remember { mutableStateOf<Highlight?>(null) }
    val style = CitationStyle.entries.firstOrNull { it.name == state.data.settings.citation } ?: CitationStyle.APA
    val list = state.data.highlights.filter { h ->
        val b = state.book(h.bookId)
        b != null && b.deletedAt == 0L && (color == null || h.color == color) && (bookFilter == null || h.bookId == bookFilter) &&
            (query.isBlank() || h.text.contains(query, true) || h.note.contains(query, true))
    }.sortedByDescending { it.createdAt }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.width(320.dp), placeholder = { Text("Search highlights and notes") })
        Chip("All colours", color == null) { color = null }
        HighlightColor.entries.forEach { c ->
            Box(Modifier.size(24.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(c.argb)).border(if (color == c.name) 2.5.dp else 1.dp, if (color == c.name) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape).clickable { color = if (color == c.name) null else c.name })
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            GhostButton(bookFilter?.let { state.book(it)?.title?.take(24) } ?: "All books", { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("All books") }, onClick = { bookFilter = null; menu = false })
                state.liveBooks.filter { b -> state.data.highlights.any { it.bookId == b.id } }.forEach { b -> DropdownMenuItem(text = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { bookFilter = b.id; menu = false }) }
            }
        }
    }
    if (list.isEmpty()) Empty("No highlights yet", "Open a book and highlight text (EPUB) or drag over an area (PDF). They all appear here.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.id }) { h ->
            val b = state.book(h.bookId)!!
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.width(5.dp).fillMaxHeight().clip(CircleShape).background(highlightColor(h.color)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${b.title} · ${h.label}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(h.text, style = MaterialTheme.typography.bodyLarge)
                        if (h.note.isNotBlank()) Muted(h.note)
                        Row {
                            TextButton(onClick = { state.updateBook(b.id) { it.copy(position = h.unit, scroll = 0f) }; nav.go(Screen.Reader(b.id)) }) { Text("Open in book") }
                            TextButton(onClick = { editing = h }) { Text("Note") }
                            TextButton(onClick = { carding = h }) { Text("Flashcard") }
                            TextButton(onClick = { copyToClipboard(Citations.quoteWithCitation(h.text, b.citationSource(), style, h.label)); state.toast("Copied with ${style.label} citation") }) { Text("Copy with citation") }
                            TextButton(onClick = { deleting = h }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    editing?.let { h -> PromptDialog("Note", h.note, multiline = true, onDone = { n -> state.update { d -> d.copy(highlights = d.highlights.map { if (it.id == h.id) it.copy(note = n) else it }) } }, onDismiss = { editing = null }) }
    carding?.let { h -> CardDialog(state, h.bookId, h.text, h.note, { carding = null }) }
    deleting?.let { h -> ConfirmDialog("Delete this highlight?", "The highlight and its note are removed. This can't be undone.", "Delete", danger = true, onConfirm = { state.update { d -> d.copy(highlights = d.highlights.filter { it.id != h.id }) } }, onDismiss = { deleting = null }) }
}

@Composable
private fun FreeNotes(state: AppState) {
    var selected by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Note?>(null) }
    val notes = state.data.notes.sortedByDescending { it.updatedAt }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("New note", {
                val n = Note(state.newId(), null, "", "", System.currentTimeMillis())
                state.update { it.copy(notes = it.notes + n) }; selected = n.id
            }, Modifier.fillMaxWidth())
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(notes, key = { it.id }) { n ->
                    Panel(Modifier.fillMaxWidth(), onClick = { selected = n.id }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(n.title.ifBlank { "Untitled note" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (selected == n.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Muted(n.body.ifBlank { "Empty" }, maxLines = 2)
                        }
                    }
                }
            }
        }
        val note = notes.firstOrNull { it.id == selected }
        if (note == null) Box(Modifier.weight(1f).fillMaxHeight(), Alignment.Center) { Muted(if (notes.isEmpty()) "Notes you write are saved on this computer. Create your first note." else "Choose a note, or create a new one.") }
        else key(note.id) {
            var title by remember { mutableStateOf(note.title) }
            var body by remember { mutableStateOf(note.body) }
            var bookId by remember { mutableStateOf(note.bookId) }
            LaunchedEffect(title, body, bookId) {
                if (title != note.title || body != note.body || bookId != note.bookId) {
                    delay(700)
                    state.update { d -> d.copy(notes = d.notes.map { if (it.id == note.id) it.copy(title = title, body = body, bookId = bookId, updatedAt = System.currentTimeMillis()) else it }) }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        GhostButton("Book: ${state.book(bookId)?.title?.take(30) ?: "none"}", { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Not linked to a book") }, onClick = { bookId = null; menu = false })
                            state.liveBooks.forEach { b -> DropdownMenuItem(text = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { bookId = b.id; menu = false }) }
                        }
                    }
                    GhostButton("Copy", { copyToClipboard("$title\n\n$body") })
                    GhostButton("Delete note", { deleting = note })
                    Muted("Saved automatically")
                }
                OutlinedTextField(body, { body = it }, modifier = Modifier.fillMaxWidth().weight(1f), placeholder = { Text("Write here…") })
            }
        }
    }
    deleting?.let { n -> ConfirmDialog("Delete this note?", "“${n.title.ifBlank { "Untitled note" }}” will be removed. This can't be undone.", "Delete", danger = true, onConfirm = { state.update { d -> d.copy(notes = d.notes.filter { it.id != n.id }) }; selected = null }, onDismiss = { deleting = null }) }
}
