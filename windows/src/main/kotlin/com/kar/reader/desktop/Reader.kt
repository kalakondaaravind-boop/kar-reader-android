package com.kar.reader.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kar.reader.core.domain.ExportBook
import com.kar.reader.core.domain.ExportHighlight
import com.kar.reader.core.domain.ExportNote
import com.kar.reader.core.domain.CitationStyle
import com.kar.reader.core.domain.ResearchExport
import com.kar.reader.core.model.HighlightColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class Jump(val unit: Int, val start: Int = -1, val end: Int = -1) { val token = System.nanoTime() }

/** State shared by the reader's toolbar, the page or chapter view, and the side panel. */
class ReaderCtl {
    var panel by mutableStateOf<String?>("contents")   // contents | highlights | bookmarks | search | null
    var color by mutableStateOf("YELLOW")
    var tool by mutableStateOf("read")                 // PDF: read | highlight | copy
    var zoom by mutableFloatStateOf(1f)                // PDF: 1.0 fits the width
    var jump by mutableStateOf<Jump?>(null)
    var unit by mutableIntStateOf(0)
    var scroll by mutableFloatStateOf(0f)
    var searcher: ((String) -> List<Hit>)? = null
    var speaking by mutableStateOf(false)
}

fun highlightColor(key: String) = Color(HighlightColor.fromKey(key).argb)

@Composable
fun ReaderScreen(state: AppState, nav: Nav, bookId: String) {
    val book = state.book(bookId)
    if (book == null) { LaunchedEffect(Unit) { nav.go(Screen.Library) }; return }
    val ctl = remember(bookId) { ReaderCtl().also { it.unit = book.position } }
    var toc by remember(bookId) { mutableStateOf<List<TocItem>>(emptyList()) }

    DisposableEffect(bookId) {
        val started = System.currentTimeMillis()
        state.updateBook(bookId) { it.copy(lastOpenedAt = started, status = if (it.status == "unread") "reading" else it.status) }
        onDispose {
            Tts.stop()
            val spent = System.currentTimeMillis() - started
            // Only time the app actually measured, and only if it was a real sitting.
            if (spent >= 20_000) state.update { it.copy(sessions = it.sessions + Session(bookId, started, spent)) }
        }
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ReaderBar(state, nav, book, ctl)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (book.isPdf) PdfReader(state, book, ctl) { toc = it } else EpubReader(state, book, ctl) { toc = it }
            }
        }
        if (ctl.panel != null) ReaderPanel(state, book, ctl, toc)
    }
}

@Composable
private fun ReaderBar(state: AppState, nav: Nav, book: Book, ctl: ReaderCtl) {
    var more by remember { mutableStateOf(false) }
    var cite by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { nav.go(Screen.Library) }) { Text("← Library") }
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (book.isPdf) {
                Chip("Read", ctl.tool == "read") { ctl.tool = "read" }
                Chip("Highlight area", ctl.tool == "highlight") { ctl.tool = "highlight" }
                Chip("Copy text", ctl.tool == "copy") { ctl.tool = "copy" }
                TextButton(onClick = { ctl.zoom = (ctl.zoom - 0.15f).coerceAtLeast(0.4f) }) { Text("−") }
                Text("${(ctl.zoom * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { ctl.zoom = (ctl.zoom + 0.15f).coerceAtMost(3f) }) { Text("+") }
            } else {
                val s = state.data.settings
                TextButton(onClick = { state.update { it.copy(settings = s.copy(epubFont = (s.epubFont - 1).coerceAtLeast(12))) } }) { Text("A−") }
                TextButton(onClick = { state.update { it.copy(settings = s.copy(epubFont = (s.epubFont + 1).coerceAtMost(34))) } }) { Text("A+") }
                Chip(if (s.epubSerif) "Serif" else "Sans", false) { state.update { it.copy(settings = s.copy(epubSerif = !s.epubSerif)) } }
                if (Tts.available) Chip(if (ctl.speaking) "■ Stop reading" else "🔊 Read aloud", ctl.speaking) { ctl.speaking = !ctl.speaking; if (!ctl.speaking) Tts.stop() }
            }
            HighlightColor.entries.forEach { c ->
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(Color(c.argb))
                        .border(if (ctl.color == c.name) 2.5.dp else 1.dp, if (ctl.color == c.name) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { ctl.color = c.name },
                )
            }
            listOf("contents" to "Contents", "highlights" to "Highlights", "bookmarks" to "Bookmarks", "search" to "Search").forEach { (k, v) ->
                Chip(v, ctl.panel == k) { ctl.panel = if (ctl.panel == k) null else k }
            }
            TextButton(onClick = { addBookmark(state, book, ctl) }) { Text("☆ Bookmark") }
            Box {
                TextButton(onClick = { more = true }) { Text("More ▾") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(if (book.status == "finished") "Mark as not finished" else "Mark as finished") }, onClick = {
                        more = false
                        state.updateBook(book.id) { if (it.status == "finished") it.copy(status = "reading", finishedAt = 0) else it.copy(status = "finished", finishedAt = System.currentTimeMillis()) }
                    })
                    DropdownMenuItem(text = { Text("Copy citation…") }, onClick = { more = false; cite = true })
                    DropdownMenuItem(text = { Text("Edit details and tags…") }, onClick = { more = false; details = true })
                    DropdownMenuItem(text = { Text("Export notes and highlights (Markdown)…") }, onClick = { more = false; exportBookNotes(state, book) })
                }
            }
        }
    }
    if (cite) CitationDialog(book, state.data.settings.citation, { st -> state.update { it.copy(settings = it.settings.copy(citation = st)) } }, { cite = false })
    if (details) BookDetailsDialog(book, { u -> state.updateBook(book.id) { u } }, { details = false })
}

fun addBookmark(state: AppState, book: Book, ctl: ReaderCtl) {
    val label = if (book.isPdf) "p. ${ctl.unit + 1}" else "Chapter ${ctl.unit + 1}"
    state.update { it.copy(bookmarks = it.bookmarks + Bookmark(state.newId(), book.id, ctl.unit, ctl.scroll, label, System.currentTimeMillis())) }
    state.toast("Bookmarked $label")
}

fun exportBookNotes(state: AppState, book: Book) {
    val f = saveFileDialog("Export notes and highlights", "${slug(book.title)}-notes.md") ?: return
    val style = CitationStyle.entries.firstOrNull { it.name == state.data.settings.citation } ?: CitationStyle.APA
    f.writeText(ResearchExport.markdown(exportBookFor(state, book), style), Charsets.UTF_8)
    state.toast("Saved ${f.name}")
}

fun exportBookFor(state: AppState, book: Book) = ExportBook(
    id = book.id, title = book.title, authors = book.authors, year = book.year, publisher = book.publisher, format = book.format, tags = book.tags,
    highlights = state.data.highlights.filter { it.bookId == book.id }.sortedWith(compareBy({ it.unit }, { it.start }, { it.createdAt }))
        .map { ExportHighlight(it.text, it.label, null, it.note.ifBlank { null }, it.createdAt) },
    notes = state.data.notes.filter { it.bookId == book.id }.map { ExportNote(it.title, it.body, null, it.updatedAt) },
)

// ───────────────────────────────── PDF ─────────────────────────────────

@Composable
private fun PdfReader(state: AppState, book: Book, ctl: ReaderCtl, onToc: (List<TocItem>) -> Unit) {
    var pdf by remember(book.id) { mutableStateOf<PdfBook?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(book.id) {
        withContext(Dispatchers.IO) {
            runCatching { PdfBook(File(Paths.books, book.file)) }
                .onSuccess { pdf = it; onToc(it.outline()) }
                .onFailure { error = it.message ?: "This PDF can't be opened." }
        }
    }
    DisposableEffect(book.id) { onDispose { runCatching { pdf?.close() } } }
    val doc = pdf
    if (doc == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { if (error != null) Empty("Can't open this book", error.orEmpty()) else CircularProgressIndicator() }
        return
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = book.position.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0)))
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            ctl.unit = idx
            ctl.scroll = if (doc.pageCount > 0) idx.toFloat() / doc.pageCount else 0f
            state.updateBook(book.id) { it.copy(position = idx, units = doc.pageCount) }
        }
    }
    LaunchedEffect(ctl.jump?.token) { ctl.jump?.let { listState.animateScrollToItem(it.unit.coerceIn(0, doc.pageCount - 1)) } }
    LaunchedEffect(doc) {
        ctl.searcher = { q -> (0 until doc.pageCount).mapNotNull { p -> snippetAround(doc.text(p), q)?.let { Hit(book.id, book.title, p, "p. ${p + 1}", it) } } }
    }
    val highlights = state.data.highlights.filter { it.bookId == book.id && it.isArea }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) false else when (e.key) {
                    Key.Equals, Key.Plus -> { ctl.zoom = (ctl.zoom + 0.15f).coerceAtMost(3f); true }
                    Key.Minus -> { ctl.zoom = (ctl.zoom - 0.15f).coerceAtLeast(0.4f); true }
                    Key.Zero -> { ctl.zoom = 1f; true }
                    Key.F -> { ctl.panel = "search"; true }
                    Key.B -> { addBookmark(state, book, ctl); true }
                    Key.H -> { ctl.tool = if (ctl.tool == "highlight") "read" else "highlight"; true }
                    else -> false
                }
            }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                    Key.PageDown, Key.Spacebar -> { scope.launch { listState.animateScrollBy(if (e.isShiftPressed) -900f else 900f) }; true }
                    Key.PageUp -> { scope.launch { listState.animateScrollBy(-900f) }; true }
                    Key.DirectionDown -> { scope.launch { listState.animateScrollBy(120f) }; true }
                    Key.DirectionUp -> { scope.launch { listState.animateScrollBy(-120f) }; true }
                    Key.DirectionRight -> { scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex + 1).coerceAtMost(doc.pageCount - 1)) }; true }
                    Key.DirectionLeft -> { scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex - 1).coerceAtLeast(0)) }; true }
                    Key.MoveHome -> { scope.launch { listState.scrollToItem(0) }; true }
                    Key.MoveEnd -> { scope.launch { listState.scrollToItem(doc.pageCount - 1) }; true }
                    else -> false
                }
            },
    ) {
        val viewport = maxWidth
        val pageWidth = ((viewport - 56.dp) * ctl.zoom).coerceAtLeast(160.dp)
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(
                state = listState, modifier = Modifier.widthIn(min = viewport), horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(doc.pageCount) { i ->
                    val (pw, ph) = remember(i) { doc.pageSize(i) }
                    val scale = (pageWidth.value * density.density / pw).coerceIn(0.3f, 3.5f)
                    val bitmap by produceState<ImageBitmap?>(null, i, scale) {
                        value = withContext(Dispatchers.Default) { runCatching { doc.render(i, scale) }.getOrNull() }
                    }
                    var boxSize by remember { mutableStateOf(IntSize.Zero) }
                    var drag by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
                    Box(Modifier.size(pageWidth, pageWidth * (ph / pw)).shadow(3.dp).background(Color.White).onSizeChanged { boxSize = it }) {
                        bitmap?.let { Image(it, contentDescription = "Page ${i + 1}", modifier = Modifier.fillMaxSize()) }
                            ?: Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Page ${i + 1}", color = Color.Gray) }
                        androidx.compose.foundation.Canvas(
                            Modifier.fillMaxSize().pointerInput(ctl.tool, i) {
                                if (ctl.tool == "read") return@pointerInput
                                detectDragGestures(
                                    onDragStart = { o -> drag = o to o },
                                    onDrag = { change, delta -> change.consume(); drag = drag?.let { it.first to (it.second + delta) } },
                                    onDragCancel = { drag = null },
                                    onDragEnd = {
                                        val d = drag; drag = null
                                        if (d != null && boxSize.width > 0 && boxSize.height > 0) {
                                            val x0 = (minOf(d.first.x, d.second.x) / boxSize.width).coerceIn(0f, 1f)
                                            val y0 = (minOf(d.first.y, d.second.y) / boxSize.height).coerceIn(0f, 1f)
                                            val x1 = (maxOf(d.first.x, d.second.x) / boxSize.width).coerceIn(0f, 1f)
                                            val y1 = (maxOf(d.first.y, d.second.y) / boxSize.height).coerceIn(0f, 1f)
                                            if ((x1 - x0) * boxSize.width > 8 && (y1 - y0) * boxSize.height > 8) {
                                                scope.launch {
                                                    val text = withContext(Dispatchers.IO) { doc.textInArea(i, x0, y0, x1 - x0, y1 - y0) }
                                                    if (ctl.tool == "copy") {
                                                        if (text.isBlank()) state.toast("No text found there. This page may be a scan.") else { copyToClipboard(text); state.toast("Copied ${text.length} characters") }
                                                    } else {
                                                        state.update { data ->
                                                            data.copy(highlights = data.highlights + Highlight(
                                                                id = state.newId(), bookId = book.id, color = ctl.color, text = text.ifBlank { "(marked area on page ${i + 1})" },
                                                                label = "p. ${i + 1}", unit = i, rx = x0, ry = y0, rw = x1 - x0, rh = y1 - y0, createdAt = System.currentTimeMillis(),
                                                            ))
                                                        }
                                                        state.toast("Highlight saved")
                                                    }
                                                }
                                            }
                                        }
                                    },
                                )
                            },
                        ) {
                            highlights.filter { it.unit == i }.forEach { h ->
                                drawRect(highlightColor(h.color).copy(alpha = 0.40f), Offset(h.rx * size.width, h.ry * size.height), Size(h.rw * size.width, h.rh * size.height))
                            }
                            drag?.let { (a, b) ->
                                val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                                drawRect(highlightColor(ctl.color).copy(alpha = 0.30f), tl, Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)))
                            }
                        }
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = RoundedCornerShape(50), shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
            Text("Page ${ctl.unit + 1} of ${doc.pageCount}", Modifier.padding(horizontal = 16.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ───────────────────────────────── EPUB ─────────────────────────────────

@Composable
private fun EpubReader(state: AppState, book: Book, ctl: ReaderCtl, onToc: (List<TocItem>) -> Unit) {
    var epub by remember(book.id) { mutableStateOf<EpubBook?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(book.id) {
        withContext(Dispatchers.IO) {
            runCatching { EpubBook(File(Paths.books, book.file)) }
                .onSuccess { b ->
                    epub = b
                    onToc(b.pub.toc.mapNotNull { t ->
                        val idx = b.chapters.indexOfFirst { it.href == t.href.substringBefore('#') }
                        if (idx >= 0) TocItem(t.title, idx, t.depth) else null
                    }.ifEmpty { b.chapters.mapIndexed { i, c -> TocItem(c.title, i, 0) } })
                }
                .onFailure { error = it.message ?: "This EPUB can't be opened." }
        }
    }
    DisposableEffect(book.id) { onDispose { runCatching { epub?.close() } } }
    val doc = epub
    if (doc == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { if (error != null) Empty("Can't open this book", error.orEmpty()) else CircularProgressIndicator() }
        return
    }
    val settings = state.data.settings
    val scope = rememberCoroutineScope()
    var chapter by remember(book.id) { mutableIntStateOf(book.position.coerceIn(0, doc.chapters.lastIndex)) }
    var restoreScroll by remember(book.id) { mutableStateOf<Float?>(book.scroll) }
    val loaded by produceState(-1 to "", chapter) { value = chapter to withContext(Dispatchers.IO) { doc.text(chapter) } }
    val text = if (loaded.first == chapter) loaded.second else ""
    var tfv by remember(chapter, text) { mutableStateOf(TextFieldValue(text)) }
    val scrollState = rememberScrollState()
    val focus = remember { FocusRequester() }
    val highlights = state.data.highlights.filter { it.bookId == book.id && !it.isArea && it.unit == chapter }

    LaunchedEffect(chapter) { ctl.unit = chapter }
    LaunchedEffect(doc) {
        ctl.searcher = { q ->
            doc.chapters.indices.flatMap { c ->
                val t = doc.text(c)
                val out = ArrayList<Hit>()
                var from = 0
                while (out.size < 4) {
                    val i = t.indexOf(q, from, ignoreCase = true)
                    if (i < 0) break
                    snippetAround(t.substring((i - 80).coerceAtLeast(0), (i + q.length + 80).coerceAtMost(t.length)), q)?.let { out += Hit(book.id, book.title, c, doc.chapters[c].title, it, i, i + q.length) }
                    from = i + q.length
                }
                out
            }
        }
    }
    LaunchedEffect(ctl.jump?.token, chapter, text) {
        val j = ctl.jump ?: return@LaunchedEffect
        if (j.unit != chapter) { restoreScroll = null; chapter = j.unit.coerceIn(0, doc.chapters.lastIndex); return@LaunchedEffect }
        if (text.isEmpty()) return@LaunchedEffect
        ctl.jump = null
        if (j.start >= 0) {
            tfv = tfv.copy(selection = TextRange(j.start.coerceIn(0, text.length), j.end.coerceIn(0, text.length)))
            snapshotFlow { scrollState.maxValue }.first { it > 0 }
            scrollState.animateScrollTo((scrollState.maxValue * (j.start.toFloat() / text.length)).toInt())
        } else {
            scrollState.scrollTo(0)
        }
    }
    LaunchedEffect(text, chapter) {
        val r = restoreScroll
        if (r != null && text.isNotEmpty()) {
            snapshotFlow { scrollState.maxValue }.first { it > 0 }
            scrollState.scrollTo((scrollState.maxValue * r).toInt())
            restoreScroll = null
        }
    }
    LaunchedEffect(chapter, text) {
        snapshotFlow { scrollState.value to scrollState.maxValue }.collect { (v, m) ->
            if (restoreScroll == null && m > 0) {
                val f = v.toFloat() / m
                ctl.scroll = (chapter + f) / doc.chapters.size
                delay(350)
                state.updateBook(book.id) { it.copy(position = chapter, scroll = f, units = doc.chapters.size) }
            }
        }
    }
    LaunchedEffect(ctl.speaking, chapter) {
        if (ctl.speaking) {
            val from = tfv.selection.min.coerceIn(0, text.length)
            Tts.speak(if (!tfv.selection.collapsed) text.substring(tfv.selection.min, tfv.selection.max) else text.substring(from), settings.ttsRate)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val family = if (settings.epubSerif) FontFamily.Serif else FontFamily.SansSerif
    val transformation = remember(highlights) {
        VisualTransformation { t ->
            val styled = buildAnnotatedString {
                append(t.text)
                highlights.forEach { h ->
                    val s = h.start.coerceIn(0, t.text.length); val e = h.end.coerceIn(s, t.text.length)
                    if (e > s) addStyle(SpanStyle(background = highlightColor(h.color).copy(alpha = 0.45f)), s, e)
                }
            }
            TransformedText(styled, OffsetMapping.Identity)
        }
    }
    fun go(delta: Int) {
        val n = (chapter + delta).coerceIn(0, doc.chapters.lastIndex)
        if (n != chapter) { restoreScroll = null; chapter = n; scope.launch { scrollState.scrollTo(0) } }
    }
    fun highlightSelection(note: String = "") {
        val sel = tfv.selection
        if (sel.collapsed || text.isEmpty()) return
        val s = sel.min.coerceIn(0, text.length); val e = sel.max.coerceIn(s, text.length)
        val quote = text.substring(s, e).replace(Regex("\\s+"), " ").trim()
        if (quote.isEmpty()) return
        state.update { d ->
            d.copy(highlights = d.highlights + Highlight(
                id = state.newId(), bookId = book.id, color = ctl.color, text = quote, note = note, label = doc.chapters[chapter].title,
                unit = chapter, start = s, end = e, createdAt = System.currentTimeMillis(),
            ))
        }
        tfv = tfv.copy(selection = TextRange(e))
    }
    var noteDialog by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) false else when (e.key) {
                    Key.Equals, Key.Plus -> { state.update { it.copy(settings = settings.copy(epubFont = (settings.epubFont + 1).coerceAtMost(34))) }; true }
                    Key.Minus -> { state.update { it.copy(settings = settings.copy(epubFont = (settings.epubFont - 1).coerceAtLeast(12))) }; true }
                    Key.F -> { ctl.panel = "search"; true }
                    Key.B -> { addBookmark(state, book, ctl); true }
                    Key.H -> { highlightSelection(); true }
                    else -> false
                }
            }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                    Key.PageDown, Key.Spacebar -> {
                        scope.launch { if (scrollState.value >= scrollState.maxValue - 4) go(1) else scrollState.animateScrollBy(if (e.isShiftPressed) -700f else 700f) }; true
                    }
                    Key.PageUp -> { scope.launch { if (scrollState.value <= 4) go(-1) else scrollState.animateScrollBy(-700f) }; true }
                    Key.DirectionDown -> { scope.launch { scrollState.animateScrollBy(90f) }; true }
                    Key.DirectionUp -> { scope.launch { scrollState.animateScrollBy(-90f) }; true }
                    Key.DirectionRight -> { go(1); true }
                    Key.DirectionLeft -> { go(-1); true }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!tfv.selection.collapsed) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Selected text:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 6.dp))
                        PrimaryButton("Highlight  (Ctrl+H)", { highlightSelection() })
                        GhostButton("Highlight with note…", { noteDialog = true })
                        GhostButton("Copy", { copyToClipboard(text.substring(tfv.selection.min, tfv.selection.max)) })
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 780.dp).fillMaxWidth()) {
                        Text(doc.chapters[chapter].title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        SpacerH(18.dp)
                        if (text.isBlank()) Muted("This section has no text (it may be an image or cover page).")
                        BasicTextField(
                            value = tfv, onValueChange = { if (it.text == tfv.text) tfv = it }, readOnly = true,
                            textStyle = TextStyle(fontFamily = family, fontSize = settings.epubFont.sp, lineHeight = (settings.epubFont * 1.65f).sp, color = MaterialTheme.colorScheme.onBackground),
                            cursorBrush = SolidColor(Color.Transparent), visualTransformation = transformation, modifier = Modifier.fillMaxWidth(),
                        )
                        SpacerH(28.dp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            GhostButton("← Previous chapter", { go(-1) }, enabled = chapter > 0)
                            Muted("Chapter ${chapter + 1} of ${doc.chapters.size}")
                            PrimaryButton("Next chapter →", { go(1) }, enabled = chapter < doc.chapters.lastIndex)
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(scrollState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
    if (noteDialog) PromptDialog("Note for this highlight", "", multiline = true, onDone = { highlightSelection(it) }, onDismiss = { noteDialog = false })
}

// ───────────────────────────────── Side panel ─────────────────────────────────

@Composable
private fun ReaderPanel(state: AppState, book: Book, ctl: ReaderCtl, toc: List<TocItem>) {
    Surface(Modifier.width(340.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        when (ctl.panel) {
            "contents" -> Column(Modifier.fillMaxSize().padding(14.dp)) {
                Text("Contents", style = MaterialTheme.typography.titleMedium)
                SpacerH(8.dp)
                if (toc.isEmpty()) Muted(if (book.isPdf) "This PDF has no outline. Use Page up/down or Search." else "No table of contents found.")
                LazyColumn {
                    items(toc) { t ->
                        Text(
                            t.title.ifBlank { "Untitled" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, color = if (ctl.unit == t.unit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable { ctl.jump = Jump(t.unit) }.padding(start = (t.depth * 14).dp, top = 7.dp, bottom = 7.dp),
                        )
                    }
                }
            }
            "highlights" -> HighlightsPanel(state, book, ctl)
            "bookmarks" -> {
                val marks = state.data.bookmarks.filter { it.bookId == book.id }.sortedBy { it.unit }
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    Text("Bookmarks", style = MaterialTheme.typography.titleMedium)
                    SpacerH(8.dp)
                    if (marks.isEmpty()) Muted("Press Ctrl+B or “☆ Bookmark” to mark where you are.")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(marks) { m ->
                            Panel(Modifier.fillMaxWidth(), onClick = { ctl.jump = Jump(m.unit) }) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text(m.label, style = MaterialTheme.typography.titleSmall); Muted(formatDate(m.createdAt)) }
                                    TextButton(onClick = { state.update { d -> d.copy(bookmarks = d.bookmarks.filter { it.id != m.id }) } }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
            }
            "search" -> SearchPanel(book, ctl)
        }
    }
}

@Composable
private fun SearchPanel(book: Book, ctl: ReaderCtl) {
    var input by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        results = emptyList()
        val search = ctl.searcher
        if (q.trim().length < 2 || search == null) return@LaunchedEffect
        busy = true
        results = withContext(Dispatchers.IO) { runCatching { search(q.trim()).take(300) }.getOrDefault(emptyList()) }
        busy = false
    }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Search this book", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("Word or phrase") })
            PrimaryButton("Go", { q = input })
        }
        Muted(if (busy) "Searching…" else if (q.isNotBlank()) "${results.size} matches" else "Offline full-text search.")
        LazyColumn {
            items(results) { h ->
                Column(Modifier.fillMaxWidth().clickable { ctl.jump = Jump(h.unit, h.start, h.end) }.padding(vertical = 7.dp)) {
                    Text(h.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted(h.snippet, maxLines = 3)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun HighlightsPanel(state: AppState, book: Book, ctl: ReaderCtl) {
    val list = state.data.highlights.filter { it.bookId == book.id }.sortedWith(compareBy({ it.unit }, { it.start }, { it.createdAt }))
    var editing by remember { mutableStateOf<Highlight?>(null) }
    var carding by remember { mutableStateOf<Highlight?>(null) }
    var deleting by remember { mutableStateOf<Highlight?>(null) }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text("Highlights  (${list.size})", style = MaterialTheme.typography.titleMedium)
        SpacerH(8.dp)
        if (list.isEmpty()) Muted(if (book.isPdf) "Choose “Highlight area”, then drag over the text you want." else "Select text in the chapter, then press Ctrl+H.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { h ->
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(highlightColor(h.color)))
                            Text(h.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(h.text, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { ctl.jump = Jump(h.unit, h.start, h.end) })
                        if (h.note.isNotBlank()) Text(h.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { editing = h }) { Text("Note") }
                            TextButton(onClick = { carding = h }) { Text("Flashcard") }
                            TextButton(onClick = { copyToClipboard(h.text) }) { Text("Copy") }
                            TextButton(onClick = { deleting = h }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    editing?.let { h -> PromptDialog("Note", h.note, multiline = true, onDone = { n -> state.update { d -> d.copy(highlights = d.highlights.map { if (it.id == h.id) it.copy(note = n) else it }) } }, onDismiss = { editing = null }) }
    carding?.let { h -> CardDialog(state, book.id, h.text, h.note, { carding = null }) }
    deleting?.let { h -> ConfirmDialog("Delete this highlight?", "The highlight and its note are removed. This can't be undone.", "Delete", danger = true, onConfirm = { state.update { d -> d.copy(highlights = d.highlights.filter { it.id != h.id }) } }, onDismiss = { deleting = null }) }
}

@Composable
fun CardDialog(state: AppState, bookId: String?, front0: String, back0: String, onDismiss: () -> Unit) {
    var front by remember { mutableStateOf(front0) }
    var back by remember { mutableStateOf(back0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New flashcard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(front, { front = it }, label = { Text("Front (the question or prompt)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(back, { back = it }, label = { Text("Back (the answer)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = front.isNotBlank() && back.isNotBlank(), onClick = {
                state.update { it.copy(cards = it.cards + Card(state.newId(), bookId, front.trim(), back.trim(), System.currentTimeMillis())) }
                state.toast("Flashcard added")
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
