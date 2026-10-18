@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kar.reader.ui.notes

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.kar.reader.core.designsystem.GlossyOrb
import com.kar.reader.core.designsystem.SectionLabel
import com.kar.reader.core.domain.CitationStyle
import com.kar.reader.core.domain.ExportBook
import com.kar.reader.core.domain.ExportHighlight
import com.kar.reader.core.domain.ExportNote
import com.kar.reader.core.model.HighlightColor
import com.kar.reader.data.db.BookEntity
import com.kar.reader.data.db.HighlightEntity
import com.kar.reader.data.db.NoteEntity
import com.kar.reader.data.db.NotebookEntity
import com.kar.reader.data.db.VocabularyEntity
import com.kar.reader.data.db.displayAuthor
import com.kar.reader.data.db.displayTitle
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.repo.AnnotationRepository
import com.kar.reader.data.repo.FlashcardRepository
import com.kar.reader.data.repo.LibraryRepository
import com.kar.reader.data.repo.NotebookRepository
import com.kar.reader.data.repo.PageTemplate
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.feature.epub.EpubSession
import com.kar.reader.feature.export.DocumentRenderers
import com.kar.reader.ui.common.Format
import com.kar.reader.ui.reader.CitationSheet
import com.kar.reader.ui.reader.HighlightCard
import com.kar.reader.ui.reader.QuickNoteDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotesUiState(
    val highlights: List<HighlightEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val notebooks: List<NotebookEntity> = emptyList(),
    val words: List<VocabularyEntity> = emptyList(),
    val books: Map<String, BookEntity> = emptyMap(),
    val meanings: Map<HighlightColor, String> = emptyMap(),
    val colorFilter: HighlightColor? = null,
    val bookFilter: String? = null,
)

@HiltViewModel
class NotesViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val profiles: ProfileRepository,
    private val annotations: AnnotationRepository,
    private val notebooks: NotebookRepository,
    private val flashcards: FlashcardRepository,
    private val library: LibraryRepository,
    private val prefs: AppPreferences,
) : ViewModel() {
    private val colorFilter = MutableStateFlow<HighlightColor?>(null)
    private val bookFilter = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    val state: StateFlow<NotesUiState> = profiles.currentId.flatMapLatest { pid ->
        combine(
            combine(annotations.observeAllHighlights(pid), annotations.observeNotes(pid), notebooks.observeNotebooks(pid)) { h, n, nb -> Triple(h, n, nb) },
            combine(flashcards.observeVocabulary(pid), library.observeLibrary(pid), annotations.observeMeanings(pid)) { w, lib, m -> Triple(w, lib, m) },
            colorFilter,
            bookFilter,
        ) { (h, n, nb), (w, lib, m), cf, bf ->
            NotesUiState(
                highlights = h.filter { (cf == null || it.colorKey == cf.name) && (bf == null || it.bookId == bf) },
                notes = n.filter { bf == null || it.bookId == bf },
                notebooks = nb,
                words = w,
                books = lib.associate { it.book.id to it.book },
                meanings = m,
                colorFilter = cf,
                bookFilter = bf,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    fun setColorFilter(c: HighlightColor?) { colorFilter.value = if (colorFilter.value == c) null else c }
    fun setBookFilter(id: String?) { bookFilter.value = if (bookFilter.value == id) null else id }

    fun deleteHighlight(h: HighlightEntity) = viewModelScope.launch { annotations.deleteHighlight(h.id) }
    fun deleteWord(w: VocabularyEntity) = viewModelScope.launch { flashcards.deleteWord(w.id) }
    fun updateWordNote(w: VocabularyEntity, note: String) = viewModelScope.launch { flashcards.updateWordNote(w, note) }

    fun saveNoteForHighlight(h: HighlightEntity, body: String) = viewModelScope.launch {
        val existing = annotations.noteForHighlight(h.id)
        annotations.saveNote(profiles.currentIdNow(), existing?.id, existing?.title ?: "Note", body, h.bookId, h.id, h.position)
    }

    suspend fun noteTextFor(h: HighlightEntity) = annotations.noteForHighlight(h.id)?.body.orEmpty()

    suspend fun newNote(): String = annotations.saveNote(profiles.currentIdNow(), null, "", "").id

    suspend fun newNotebook(title: String, template: PageTemplate): String = notebooks.create(profiles.currentIdNow(), title, template).id

    fun deleteNotebook(n: NotebookEntity) = viewModelScope.launch { notebooks.delete(n.id) }

    suspend fun citationStyle(): CitationStyle = prefs.settings.first().citationStyle

    /** Location label for a highlight: page number for PDFs, chapter title for EPUBs. */
    fun location(h: HighlightEntity, book: BookEntity?): String = if (book?.format == "PDF") (h.position + 1).toString() else chapterTitles[h.bookId]?.getOrNull(h.position) ?: "Chapter ${h.position + 1}"

    private val chapterTitles = HashMap<String, List<String>>()

    suspend fun loadChapterTitles(book: BookEntity) {
        if (book.format != "EPUB" || chapterTitles.containsKey(book.id)) return
        runCatching {
            EpubSession.open(library.fileFor(book)).use { s -> chapterTitles[book.id] = s.spineHrefs.indices.map { s.chapterLabel(it) } }
        }
    }

    /** Builds the printable notes summary PDF for one book. */
    fun printSummary(book: BookEntity, destination: Uri) = viewModelScope.launch {
        runCatching {
            loadChapterTitles(book)
            val pid = profiles.currentIdNow()
            val meanings = annotations.observeMeanings(pid).first()
            val hl = annotations.highlightsFor(pid, book.id)
            val notesByHighlight = annotations.notesForBook(pid, book.id).associateBy { it.highlightId }
            val export = ExportBook(
                id = book.id, title = book.displayTitle,
                authors = book.displayAuthor?.split(Regex("\\s*(,|&)\\s*"))?.filter { it.isNotBlank() }.orEmpty(),
                year = book.year, publisher = book.publisher, format = book.format, tags = library.tagsFor(book.id),
                highlights = hl.map { h ->
                    ExportHighlight(h.text, location(h, book), meanings[HighlightColor.fromKey(h.colorKey)], notesByHighlight[h.id]?.body, h.createdAt)
                },
                notes = annotations.notesForBook(pid, book.id).filter { it.highlightId == null }.map { ExportNote(it.title, it.body, it.position?.let { p -> "Chapter/page ${p + 1}" }, it.modifiedAt) },
            )
            DocumentRenderers.notesSummaryPdf(context, export, citationStyle(), destination)
        }.onSuccess { message.value = "Notes summary saved" }.onFailure { message.value = "Couldn't save the summary: ${it.message}" }
    }
}

@Composable
fun NotesScreen(
    onOpenNote: (String) -> Unit,
    onOpenNotebook: (String) -> Unit,
    onOpenBook: (String) -> Unit,
    onQuoteCard: (String) -> Unit,
    onExport: () -> Unit,
    vm: NotesViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var citeFor by remember { mutableStateOf<HighlightEntity?>(null) }
    var citeStyle by remember { mutableStateOf(CitationStyle.APA) }
    var noteFor by remember { mutableStateOf<Pair<HighlightEntity, String>?>(null) }
    var newNotebook by remember { mutableStateOf(false) }
    var summaryBook by remember { mutableStateOf<BookEntity?>(null) }
    var wordFor by remember { mutableStateOf<VocabularyEntity?>(null) }
    val summaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val b = summaryBook
        if (uri != null && b != null) vm.printSummary(b, uri)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("KAR READER")
                Text("Highlights & notes", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            }
            GlossyButton("Export", onClick = onExport, kind = GlossyKind.PEARL, icon = Icons.Filled.Upload, compact = true)
        }
        TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Highlights") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Notes") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Notebooks") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Words") })
        }
        message?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold) }
        when (tab) {
            0 -> HighlightsTab(state, vm, onOpenBook, onCite = { h -> scope.launch { citeStyle = vm.citationStyle(); state.books[h.bookId]?.let { vm.loadChapterTitles(it) }; citeFor = h } },
                onQuote = { onQuoteCard(it.id) }, onNote = { h -> scope.launch { noteFor = h to vm.noteTextFor(h) } },
                onPrint = { book -> summaryBook = book; summaryLauncher.launch("${book.displayTitle} - notes summary.pdf") })
            1 -> NotesTab(state, onOpenNote, onNew = { scope.launch { onOpenNote(vm.newNote()) } })
            2 -> NotebooksTab(state, onOpenNotebook, onNew = { newNotebook = true }, onDelete = vm::deleteNotebook)
            else -> WordsTab(state, onEdit = { wordFor = it }, onDelete = vm::deleteWord)
        }
    }

    citeFor?.let { h -> state.books[h.bookId]?.let { book -> CitationSheet(book, h.text, vm.location(h, book), citeStyle, onStyleChange = {}, onDismiss = { citeFor = null }) } }
    noteFor?.let { (h, text) -> QuickNoteDialog("Note", text, onSave = { vm.saveNoteForHighlight(h, it) }, onDismiss = { noteFor = null }) }
    wordFor?.let { w -> QuickNoteDialog("What “${w.word}” means", w.note, onSave = { vm.updateWordNote(w, it) }, onDismiss = { wordFor = null }) }
    if (newNotebook) {
        var title by remember { mutableStateOf("") }
        var template by remember { mutableStateOf(PageTemplate.GRID) }
        AlertDialog(
            onDismissRequest = { newNotebook = false },
            title = { Text("New notebook") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PageTemplate.entries.forEach { t -> FilterChip(selected = template == t, onClick = { template = t }, label = { Text(t.label) }) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { newNotebook = false; scope.launch { onOpenNotebook(vm.newNotebook(title, template)) } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newNotebook = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HighlightsTab(
    state: NotesUiState,
    vm: NotesViewModel,
    onOpenBook: (String) -> Unit,
    onCite: (HighlightEntity) -> Unit,
    onQuote: (HighlightEntity) -> Unit,
    onNote: (HighlightEntity) -> Unit,
    onPrint: (BookEntity) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HighlightColor.entries) { c ->
                    FilterChip(
                        selected = state.colorFilter == c, onClick = { vm.setColorFilter(c) },
                        label = { Text(state.meanings[c] ?: c.defaultMeaning) },
                        leadingIcon = { GlossyOrb(Color(c.argb), Modifier.size(16.dp)) },
                    )
                }
            }
        }
        val booksWithHighlights = state.highlights.map { it.bookId }.distinct().mapNotNull { state.books[it] }
        if (booksWithHighlights.size > 1 || state.bookFilter != null) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(if (state.bookFilter != null) listOfNotNull(state.books[state.bookFilter]) else booksWithHighlights, key = { it.id }) { b ->
                    FilterChip(selected = state.bookFilter == b.id, onClick = { vm.setBookFilter(b.id) }, label = { Text(b.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
        if (state.highlights.isEmpty()) item {
            EmptyState("No highlights yet", "Select text in an EPUB, or use “Highlight text” in a PDF. Name what each colour means in Settings.")
        }
        state.highlights.groupBy { it.bookId }.forEach { (bookId, list) ->
            val book = state.books[bookId]
            item(key = "header-$bookId") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(book?.displayTitle ?: "Book in recycle bin", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (book != null) {
                        TextButton(onClick = { onOpenBook(book.id) }) { Text("Open") }
                        TextButton(onClick = { onPrint(book) }) { Icon(Icons.Filled.Print, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Print summary") }
                    }
                }
            }
            items(list, key = { it.id }) { h ->
                HighlightCard(
                    h, if (book?.format == "PDF") "Page ${h.position + 1}" else "Chapter ${h.position + 1}", state.meanings,
                    onGo = book?.let { { onOpenBook(it.id) } }, onCite = { onCite(h) }, onQuote = { onQuote(h) }, onNote = { onNote(h) }, onDelete = { vm.deleteHighlight(h) },
                )
            }
        }
    }
}

@Composable
private fun NotesTab(state: NotesUiState, onOpenNote: (String) -> Unit, onNew: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item { GlossyButton("New note", onClick = onNew, icon = Icons.Filled.Add) }
        if (state.notes.isEmpty()) item { EmptyState("No notes yet", "Write notes on their own or attach them to highlights. Every edit is kept in the note's history.") }
        items(state.notes, key = { it.id }) { n ->
            GlassCard(Modifier.fillMaxWidth(), onClick = { onOpenNote(n.id) }) {
                Text(n.title.ifBlank { "Untitled note" }, fontWeight = FontWeight.SemiBold)
                if (n.body.isNotBlank()) Text(n.body, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(n.bookId?.let { state.books[it]?.displayTitle }, "edited ${Format.relative(n.modifiedAt)}").joinToString(" · "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun NotebooksTab(state: NotesUiState, onOpen: (String) -> Unit, onNew: () -> Unit, onDelete: (NotebookEntity) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item { GlossyButton("New notebook", onClick = onNew, icon = Icons.Filled.Draw) }
        if (state.notebooks.isEmpty()) item { EmptyState("No notebooks yet", "Handwrite with a stylus on blank, lined, grid or dotted pages. Shapes can be straightened and strokes moved with the lasso.") }
        items(state.notebooks, key = { it.id }) { nb ->
            GlassCard(Modifier.fillMaxWidth(), onClick = { onOpen(nb.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Draw, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nb.title, fontWeight = FontWeight.SemiBold)
                        Text("edited ${Format.relative(nb.modifiedAt)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onDelete(nb) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun WordsTab(state: NotesUiState, onEdit: (VocabularyEntity) -> Unit, onDelete: (VocabularyEntity) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        if (state.words.isEmpty()) item { EmptyState("No saved words", "Select a word in an EPUB and choose “Save word”. Each word becomes a flashcard.") }
        items(state.words, key = { it.id }) { w ->
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.word, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        if (w.note.isNotBlank()) Text(w.note)
                        if (w.context.isNotBlank()) Text("“${w.context}”", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onEdit(w) }) { Text(if (w.note.isBlank()) "Add meaning" else "Edit") }
                    TextButton(onClick = { onDelete(w) }) { Text("Delete") }
                }
            }
        }
    }
}

