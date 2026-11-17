package com.kar.reader.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kar.reader.core.domain.CitationSource
import com.kar.reader.core.domain.CitationStyle
import com.kar.reader.core.domain.Citations
import java.io.File

fun Book.citationSource() = CitationSource(authors, title, year, publisher)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(state: AppState, nav: Nav) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("recent") }
    var sortMenu by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<Book?>(null) }
    var cite by remember { mutableStateOf<Book?>(null) }
    var binConfirm by remember { mutableStateOf<Book?>(null) }
    var deepSearch by remember { mutableStateOf(false) }

    val books = state.liveBooks
    val allTags = books.flatMap { it.tags }.distinct().sorted()
    val shown = books.filter { b ->
        (query.isBlank() || b.title.contains(query, true) || b.authorLine.contains(query, true) || b.tags.any { it.contains(query, true) }) &&
            when (filter) { "reading" -> b.status == "reading"; "unread" -> b.status == "unread"; "finished" -> b.status == "finished"; else -> true } &&
            (tag == null || tag in b.tags)
    }.let { list ->
        when (sort) {
            "title" -> list.sortedBy { it.title.lowercase() }
            "author" -> list.sortedBy { it.authorLine.lowercase() }
            "added" -> list.sortedByDescending { it.addedAt }
            "progress" -> list.sortedByDescending { it.progress }
            else -> list.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }
        }
    }
    fun addFiles(files: List<File>) {
        if (files.isEmpty()) return
        val r = state.importFiles(files)
        state.toast(buildString {
            append("Added ${r.added.size} book${if (r.added.size == 1) "" else "s"}")
            if (r.skipped.isNotEmpty()) append(" · skipped ${r.skipped.size}: ${r.skipped.first()}")
        })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Title("Library", Modifier.weight(1f))
            GhostButton("Add folder", { pickFolder("Add every PDF and EPUB in a folder")?.let { dir -> addFiles(dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("pdf", "epub") }.toList()) } })
            PrimaryButton("Add books", { addFiles(pickFiles("Add books", "PDF and EPUB books", "pdf", "epub")) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.width(340.dp),
                placeholder = { Text("Search title, author or tag") }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            GhostButton("Search inside all books", { deepSearch = true })
            Box {
                GhostButton("Sort: ${mapOf("recent" to "Recent", "title" to "Title", "author" to "Author", "added" to "Date added", "progress" to "Progress")[sort]}", { sortMenu = true })
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf("recent" to "Recent", "title" to "Title", "author" to "Author", "added" to "Date added", "progress" to "Progress").forEach { (k, v) ->
                        DropdownMenuItem(text = { Text(v) }, onClick = { sort = k; sortMenu = false })
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "All", "reading" to "Reading", "unread" to "Not started", "finished" to "Finished").forEach { (k, v) -> Chip(v, filter == k) { filter = k } }
            if (allTags.isNotEmpty()) Box(Modifier.width(1.dp).size(1.dp))
            allTags.forEach { t -> Chip("# $t", tag == t) { tag = if (tag == t) null else t } }
        }

        val last = books.filter { it.lastOpenedAt > 0 && it.status != "finished" }.maxByOrNull { it.lastOpenedAt }
        if (last != null && query.isBlank() && filter == "all" && tag == null) {
            Panel(Modifier.fillMaxWidth(), onClick = { nav.go(Screen.Reader(last.id)) }) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(last, Modifier.width(54.dp).aspectRatio(0.68f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Continue reading", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(last.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted("${(last.progress * 100).toInt()}% · ${last.authorLine.ifBlank { "Unknown author" }}", maxLines = 1)
                        ProgressBar(last.progress, Modifier.width(280.dp))
                    }
                    PrimaryButton("Continue", { nav.go(Screen.Reader(last.id)) })
                }
            }
        }

        if (books.isEmpty()) {
            Empty("Your library is empty", "Add PDF or EPUB books. They are copied into KAR READER's own folder and never leave this computer.") {
                PrimaryButton("Add books", { addFiles(pickFiles("Add books", "PDF and EPUB books", "pdf", "epub")) })
            }
        } else if (shown.isEmpty()) {
            Empty("No books match", "Try clearing the search or filters.")
        } else {
            LazyVerticalGrid(GridCells.Adaptive(176.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { b ->
                    ContextMenuArea(items = {
                        listOf(
                            ContextMenuItem("Open") { nav.go(Screen.Reader(b.id)) },
                            ContextMenuItem(if (b.status == "finished") "Mark as not finished" else "Mark as finished") {
                                state.updateBook(b.id) { if (it.status == "finished") it.copy(status = "reading", finishedAt = 0) else it.copy(status = "finished", finishedAt = System.currentTimeMillis()) }
                            },
                            ContextMenuItem("Edit details and tags…") { details = b },
                            ContextMenuItem("Copy citation…") { cite = b },
                            ContextMenuItem("Move to recycle bin") { binConfirm = b },
                        )
                    }) {
                        Column(Modifier.clickable { nav.go(Screen.Reader(b.id)) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Cover(b, Modifier.fillMaxWidth().aspectRatio(0.68f))
                            Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Muted(b.authorLine.ifBlank { b.format }, maxLines = 1)
                            if (b.progress > 0f) ProgressBar(b.progress)
                        }
                    }
                }
            }
        }
    }

    details?.let { b -> BookDetailsDialog(b, { u -> state.updateBook(b.id) { u } }, { details = null }) }
    cite?.let { b -> CitationDialog(b, state.data.settings.citation, { style -> state.update { it.copy(settings = it.settings.copy(citation = style)) } }, { cite = null }) }
    binConfirm?.let { b -> ConfirmDialog("Move to recycle bin?", "“${b.title}” moves to the recycle bin with its highlights, bookmarks and cards. Nothing is deleted yet.", "Move to bin", onConfirm = { state.moveToBin(b.id) }, onDismiss = { binConfirm = null }) }
    if (deepSearch) FullTextSearchDialog(state, onOpen = { id, unit -> state.updateBook(id) { it.copy(position = unit, scroll = 0f) }; deepSearch = false; nav.go(Screen.Reader(id)) }, onDismiss = { deepSearch = false })
}

@Composable
fun BookDetailsDialog(book: Book, onSave: (Book) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(book.title) }
    var authors by remember { mutableStateOf(book.authors.joinToString("; ")) }
    var year by remember { mutableStateOf(book.year.orEmpty()) }
    var publisher by remember { mutableStateOf(book.publisher.orEmpty()) }
    var tags by remember { mutableStateOf(book.tags.joinToString(", ")) }
    var rating by remember { mutableStateOf(book.rating) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Book details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(authors, { authors = it }, label = { Text("Authors (separate with ;)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(year, { year = it }, label = { Text("Year") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(publisher, { publisher = it }, label = { Text("Publisher") }, modifier = Modifier.weight(2f))
                }
                OutlinedTextField(tags, { tags = it }, label = { Text("Tags (separate with commas)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Rating", Modifier.padding(end = 8.dp))
                    (1..5).forEach { n -> Text(if (n <= rating) "★" else "☆", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable { rating = if (rating == n) 0 else n }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(book.copy(
                    title = title.ifBlank { book.title }, authors = authors.split(';').map { it.trim() }.filter { it.isNotEmpty() },
                    year = year.trim().ifEmpty { null }, publisher = publisher.trim().ifEmpty { null },
                    tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct(), rating = rating,
                ))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CitationDialog(book: Book, styleName: String, onStyle: (String) -> Unit, onDismiss: () -> Unit) {
    val style = CitationStyle.entries.firstOrNull { it.name == styleName } ?: CitationStyle.APA
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Citation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CitationStyle.entries.forEach { s -> Chip(s.label, s == style) { onStyle(s.name) } } }
                Panel(Modifier.fillMaxWidth()) { Text(Citations.bibliography(book.citationSource(), style), Modifier.padding(14.dp)) }
                Muted("In text: " + Citations.inText(book.citationSource(), style, null))
                Muted("Check authors, year and publisher in Book details first: PDFs often carry none.")
            }
        },
        confirmButton = { TextButton(onClick = { copyToClipboard(Citations.bibliography(book.citationSource(), style)) }) { Text("Copy reference") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
