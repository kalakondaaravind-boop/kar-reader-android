@file:OptIn(ExperimentalMaterial3Api::class)

package com.kar.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kar.reader.core.designsystem.EmptyState
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyProgress
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.core.input.PageTurn
import com.kar.reader.core.model.HighlightColor
import com.kar.reader.data.db.HighlightEntity
import com.kar.reader.data.db.displayTitle
import com.kar.reader.feature.epub.KarWebView
import com.kar.reader.feature.epub.ReaderTheme
import com.kar.reader.feature.epub.SelectionAction
import com.kar.reader.feature.epub.readerCss
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

private suspend fun KarWebView.eval(js: String): String? = suspendCancellableCoroutine { cont ->
    evaluateJavascript(js) { cont.resume(it) }
}

@Composable
fun EpubReaderScreen(onBack: () -> Unit, onQuoteCard: (String) -> Unit, vm: EpubReaderViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val prefs by vm.readerPrefs.collectAsStateWithLifecycle()
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val meanings by vm.meanings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        vm.pageTurns.readerShown()
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) vm.onResume()
            if (e == Lifecycle.Event.ON_PAUSE) vm.onPause()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.onResume()
        onDispose { lifecycle.removeObserver(observer); vm.onPause(); vm.pageTurns.readerHidden() }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    val session = state.session
    val book = state.book
    if (session == null || book == null) {
        Column(Modifier.fillMaxSize()) {
            KarTopBar(title = book?.displayTitle ?: "EPUB", onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
            state.error?.let { EmptyState("Can't open this book", it, action = { GlossyButton("Back to library", onClick = onBack) }) }
                ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LinearProgressIndicator() }
        }
        return
    }

    val theme = vm.effectiveTheme(prefs, settings.einkMode)
    val css = readerCss(theme, prefs?.lineHeight ?: 1.6f, prefs?.marginEm ?: 1.2f)
    val chapterHighlights = highlights.filter { it.position == state.chapter }
    val webView = remember { KarWebView(context) }
    val latestHighlights by rememberUpdatedState(chapterHighlights)
    var pendingSelection by remember { mutableStateOf<Pair<SelectionAction, EpubSelection>?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var citeFor by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var noteFor by remember { mutableStateOf<Pair<HighlightEntity, String>?>(null) }
    var progress by remember { mutableFloatStateOf(vm.currentProgression()) }
    var flash by remember { mutableIntStateOf(0) }
    var turns by remember { mutableIntStateOf(0) }

    suspend fun applyHighlights() {
        webView.eval("window.__kar && __kar.clearHighlights()")
        latestHighlights.forEach { h ->
            val (start, len) = h.locator.split(':').mapNotNull { it.toIntOrNull() }.let { if (it.size == 2) it[0] to it[1] else return@forEach }
            webView.eval("window.__kar && __kar.highlight(${JSONObject.quote(h.id)}, $start, $len, ${JSONObject.quote(HighlightColor.fromKey(h.colorKey).css)})")
        }
    }

    // Wire up the WebView once.
    DisposableEffect(webView) {
        webView.session = session
        webView.onExternalLinkBlocked = { scope.launch { snackbar.showSnackbar("Links to websites are off: KAR READER works offline.") } }
        webView.onLinkToChapter = { href -> vm.goToHref(href) }
        webView.onSelectionAction = { action ->
            scope.launch {
                val sel = vm.parseSelection(webView.eval("window.__kar && __kar.selection()"))
                webView.eval("window.__kar && __kar.clearSelection()")
                if (sel != null) pendingSelection = action to sel
            }
        }
        onDispose { webView.stopLoading(); webView.destroy() }
    }
    webView.onPageLoaded = {
        scope.launch {
            delay(60)
            applyHighlights()
            val s = vm.state.value
            if (s.pendingFragment != null) {
                webView.eval("var e=document.getElementById(${JSONObject.quote(s.pendingFragment)}); if(e) e.scrollIntoView();")
            } else if (s.pendingProgression > 0f) {
                // Layout may still settle (images, fonts), so restore twice.
                webView.eval("__kar.setProgress(${s.pendingProgression})")
                delay(400)
                webView.eval("__kar.setProgress(${s.pendingProgression})")
            }
        }
    }

    // Load the chapter whenever the target or the typography changes.
    LaunchedEffect(state.loadToken, css, prefs?.fontScalePercent) {
        webView.chapterCss = css
        webView.settings.textZoom = prefs?.fontScalePercent ?: 100
        webView.setBackgroundColor(android.graphics.Color.parseColor(theme.background))
        webView.loadUrl(session.urlFor(session.spineHrefs[state.chapter]))
    }
    LaunchedEffect(chapterHighlights.map { it.id to it.colorKey }) { applyHighlights() }
    // Track the reading position.
    LaunchedEffect(state.loadToken) {
        while (true) {
            delay(1_200)
            webView.eval("window.__kar ? __kar.progress() : -1")?.toFloatOrNull()?.takeIf { it >= 0f }?.let { p -> progress = p; vm.onProgression(p) }
        }
    }
    LaunchedEffect(settings.einkMode) {
        vm.pageTurns.turns.collectLatest { turn ->
            val moved = webView.eval("window.__kar ? __kar.page(${if (turn == PageTurn.NEXT) 1 else -1}) : false") == "true"
            if (!moved) { if (turn == PageTurn.NEXT) vm.nextChapter() else vm.previousChapter(toEnd = true) }
            if (settings.einkMode) { turns++; if (turns % settings.einkRefreshPages.coerceAtLeast(1) == 0) flash++ }
        }
    }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(theme.background)))) {
        Column(Modifier.fillMaxSize()) {
            KarTopBar(
                title = book.displayTitle,
                subtitle = vm.locationLabel(),
                onBack = onBack,
                backIcon = Icons.AutoMirrored.Filled.ArrowBack,
                actions = {
                    IconButton(onClick = { showSearch = true }) { Icon(Icons.Filled.Search, "Search in this chapter") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.FormatSize, "Reading settings") }
                    val marked = bookmarks.any { it.position == state.chapter && kotlin.math.abs(it.progression - progress) < 0.03f }
                    IconButton(onClick = vm::toggleBookmark) { Icon(if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Bookmark") }
                    IconButton(onClick = { showContents = true }) { Icon(Icons.AutoMirrored.Filled.List, "Contents, bookmarks and highlights") }
                },
            )
            AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.previousChapter() }, enabled = state.chapter > 0) { Text("‹ Previous") }
                Spacer(Modifier.width(12.dp))
                val total = (state.chapter + progress) / session.spineHrefs.size
                GlossyProgress(total, Modifier.weight(1f))
                Text("  ${(total * 100).toInt()}%  ", fontSize = 13.sp)
                OutlinedButton(onClick = { vm.nextChapter() }, enabled = state.chapter < session.spineHrefs.size - 1) { Text("Next ›") }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
        EinkFlash(flash)
    }

    pendingSelection?.let { (action, sel) ->
        when (action) {
            SelectionAction.HIGHLIGHT -> HighlightColorDialog(sel.text, meanings, onPick = { vm.addHighlight(sel, it) }, onDismiss = { pendingSelection = null })
            SelectionAction.NOTE -> QuickNoteDialog("Note on “${sel.text.take(40)}”", "", onSave = { vm.saveNoteForSelection(sel, it) }, onDismiss = { pendingSelection = null })
            SelectionAction.WORD -> { vm.saveWord(sel); pendingSelection = null }
            SelectionAction.CITE -> { citeFor = sel.text to vm.locationLabel(); pendingSelection = null }
            SelectionAction.QUOTE -> { vm.addHighlight(sel, HighlightColor.GREEN) { onQuoteCard(it.id) }; pendingSelection = null }
        }
    }
    citeFor?.let { (quote, loc) -> CitationSheet(book, quote, loc, settings.citationStyle, onStyleChange = {}, onDismiss = { citeFor = null }) }
    noteFor?.let { (h, text) -> QuickNoteDialog("Note", text, onSave = { vm.saveNoteForHighlight(h, it) }, onDismiss = { noteFor = null }) }

    if (showSettings) {
        EpubSettingsSheet(
            fontScale = prefs?.fontScalePercent ?: 100,
            lineHeight = prefs?.lineHeight ?: 1.6f,
            margin = prefs?.marginEm ?: 1.2f,
            theme = theme,
            einkLocked = settings.einkMode,
            onFontScale = { v -> vm.updatePrefs { it.copy(fontScalePercent = v) } },
            onLineHeight = { v -> vm.updatePrefs { it.copy(lineHeight = v) } },
            onMargin = { v -> vm.updatePrefs { it.copy(marginEm = v) } },
            onTheme = { t -> vm.updatePrefs { it.copy(theme = t.name) } },
            onDismiss = { showSettings = false; vm.reloadHere() },
        )
    }
    if (showContents) {
        EpubContentsSheet(
            toc = session.publication.toc.map { Triple(it.title, it.href, it.depth) },
            bookmarks = bookmarks.map { it.label to it },
            highlights = highlights,
            meanings = meanings,
            chapterLabel = { session.chapterLabel(it) },
            onToc = { href -> showContents = false; vm.goToHref(href) },
            onBookmark = { b -> showContents = false; vm.goToChapter(b.position, b.progression) },
            onDeleteBookmark = vm::deleteBookmark,
            onHighlight = { h -> showContents = false; vm.goToChapter(h.position) },
            onCite = { h -> citeFor = h.text to session.chapterLabel(h.position) },
            onQuote = { h -> onQuoteCard(h.id) },
            onNote = { h -> scope.launch { noteFor = h to vm.noteTextFor(h) } },
            onDeleteHighlight = vm::deleteHighlight,
            onDismiss = { showContents = false },
        )
    }
    if (showSearch) {
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text("Find in this chapter") },
            text = { OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Word or phrase") }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val found = webView.eval("window.__kar ? __kar.find(${JSONObject.quote(query)}) : 0") == "1"
                        if (!found) snackbar.showSnackbar("Not found in this chapter")
                    }
                }, enabled = query.isNotBlank()) { Text("Find next") }
            },
            dismissButton = { TextButton(onClick = { showSearch = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun EpubSettingsSheet(
    fontScale: Int, lineHeight: Float, margin: Float, theme: ReaderTheme, einkLocked: Boolean,
    onFontScale: (Int) -> Unit, onLineHeight: (Float) -> Unit, onMargin: (Float) -> Unit, onTheme: (ReaderTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Saved for this book", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onFontScale((fontScale - 10).coerceAtLeast(60)) }) { Text("A", fontFamily = FontFamily.Serif, fontSize = 14.sp) }
                Text("Text size $fontScale%", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                OutlinedButton(onClick = { onFontScale((fontScale + 10).coerceAtMost(250)) }) { Text("A", fontFamily = FontFamily.Serif, fontSize = 22.sp) }
            }
            Text("Theme")
            if (einkLocked) Text("E-ink mode is on, so pages use pure black on white. Turn it off in Settings › Reading to choose a theme.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.filter { it != ReaderTheme.EINK }.forEach { t -> FilterChip(selected = theme == t, onClick = { onTheme(t) }, label = { Text(t.label) }) }
            }
            Text("Line spacing ${"%.1f".format(lineHeight)}")
            Slider(lineHeight, onLineHeight, valueRange = 1.1f..2.4f, steps = 12)
            Text("Margins ${"%.1f".format(margin)} em")
            Slider(margin, onMargin, valueRange = 0f..4f, steps = 15)
            Text("Changes apply when you close this panel. Book layout and navigation are not altered.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EpubContentsSheet(
    toc: List<Triple<String, String, Int>>,
    bookmarks: List<Pair<String, com.kar.reader.data.db.BookmarkEntity>>,
    highlights: List<HighlightEntity>,
    meanings: Map<HighlightColor, String>,
    chapterLabel: (Int) -> String,
    onToc: (String) -> Unit,
    onBookmark: (com.kar.reader.data.db.BookmarkEntity) -> Unit,
    onDeleteBookmark: (com.kar.reader.data.db.BookmarkEntity) -> Unit,
    onHighlight: (HighlightEntity) -> Unit,
    onCite: (HighlightEntity) -> Unit,
    onQuote: (HighlightEntity) -> Unit,
    onNote: (HighlightEntity) -> Unit,
    onDeleteHighlight: (HighlightEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Contents") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Highlights · ${highlights.size}") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Bookmarks · ${bookmarks.size}") })
        }
        LazyColumn(Modifier.height(520.dp), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> {
                    if (toc.isEmpty()) item { Text("This book has no table of contents. Use Previous and Next to move between chapters.") }
                    items(toc) { (title, href, depth) ->
                        Text(
                            title, Modifier.fillMaxWidth().clickable { onToc(href) }.padding(start = (depth * 16).dp, top = 10.dp, bottom = 10.dp),
                            fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                1 -> {
                    if (highlights.isEmpty()) item { Text("Select text and choose Highlight. Highlights become flashcards automatically (you can turn this off in Settings).") }
                    items(highlights, key = { it.id }) { h ->
                        HighlightCard(h, chapterLabel(h.position), meanings, onGo = { onHighlight(h) }, onCite = { onCite(h) }, onQuote = { onQuote(h) }, onNote = { onNote(h) }, onDelete = { onDeleteHighlight(h) })
                    }
                }
                else -> {
                    if (bookmarks.isEmpty()) item { Text("No bookmarks yet.") }
                    items(bookmarks, key = { it.second.id }) { (label, b) ->
                        Row(Modifier.fillMaxWidth().clickable { onBookmark(b) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(12.dp))
                            Text(label, Modifier.weight(1f))
                            TextButton(onClick = { onDeleteBookmark(b) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}
