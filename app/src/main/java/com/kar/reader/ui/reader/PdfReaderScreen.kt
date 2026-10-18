@file:OptIn(ExperimentalMaterial3Api::class)

package com.kar.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kar.reader.core.designsystem.EmptyState
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyOrb
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.core.designsystem.LocalKarStyle
import com.kar.reader.core.domain.InkCodec
import com.kar.reader.core.domain.InkPoint
import com.kar.reader.core.input.PageTurn
import com.kar.reader.core.model.HighlightColor
import com.kar.reader.core.model.InkPalette
import com.kar.reader.data.db.HighlightEntity
import com.kar.reader.data.db.InkStrokeEntity
import com.kar.reader.data.db.displayTitle
import com.kar.reader.feature.pdf.PdfDocumentSession
import com.kar.reader.feature.pdf.PdfExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun PdfReaderScreen(onBack: () -> Unit, onQuoteCard: (String) -> Unit, vm: PdfReaderViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val strokes by vm.strokes.collectAsStateWithLifecycle()
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val meanings by vm.meanings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        vm.pageTurns.readerShown()
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) vm.onResume()
            if (e == Lifecycle.Event.ON_PAUSE) vm.onPause()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            vm.onPause()
            vm.pageTurns.readerHidden()
        }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    val session = state.session
    val title = state.book?.displayTitle ?: ""
    if (session == null) {
        Column(Modifier.fillMaxSize()) {
            KarTopBar(title = title.ifEmpty { "PDF" }, onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
            state.error?.let { EmptyState("Can't open this PDF", it, action = { GlossyButton("Back to library", onClick = onBack) }) }
                ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LinearProgressIndicator() }
        }
        return
    }

    val listState = key(session) { rememberLazyListState(state.initialPage) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var showSheet by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var areaColor by rememberSaveable { mutableStateOf(HighlightColor.YELLOW) }
    var citeFor by remember { mutableStateOf<HighlightEntity?>(null) }
    var noteFor by remember { mutableStateOf<Pair<HighlightEntity, String>?>(null) }
    var flash by remember { mutableIntStateOf(0) }
    var turns by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val currentPage by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let(vm::export) }

    // Restore the exact scroll offset within the page once, then track position.
    LaunchedEffect(session) {
        val size = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size
        if (size != null && state.initialOffset > 0f) listState.scrollToItem(state.initialPage, (size * state.initialOffset).toInt())
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                val h = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 1
                vm.onPosition(index, (offset.toFloat() / h).coerceIn(0f, 1f))
            }
    }
    // Page-turn keys, remotes and the e-ink refresh flash.
    LaunchedEffect(session, settings.einkMode) {
        vm.pageTurns.turns.collectLatest { turn ->
            val target = (listState.firstVisibleItemIndex + if (turn == PageTurn.NEXT) 1 else -1).coerceIn(0, session.pageCount - 1)
            if (settings.einkMode) {
                listState.scrollToItem(target)
                turns++
                if (turns % settings.einkRefreshPages.coerceAtLeast(1) == 0) flash++
            } else {
                listState.animateScrollToItem(target)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            KarTopBar(
                title = title,
                subtitle = "Page ${currentPage + 1} of ${session.pageCount} · ${(zoom * 100).toInt()}%",
                onBack = onBack,
                backIcon = Icons.AutoMirrored.Filled.ArrowBack,
                actions = {
                    val marked = bookmarks.any { it.position == currentPage }
                    IconButton(onClick = { vm.toggleBookmark(currentPage) }) {
                        Icon(if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (marked) "Remove bookmark" else "Bookmark this page")
                    }
                    IconButton(onClick = { showSheet = true }) { Icon(Icons.AutoMirrored.Filled.List, "Bookmarks and highlights") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Go to page…") }, onClick = { goTo = true; menuOpen = false })
                            DropdownMenuItem(text = { Text("Save a copy with annotations…") }, onClick = {
                                menuOpen = false
                                exportLauncher.launch("${title.ifBlank { "document" }} (annotated).pdf")
                            })
                        }
                    }
                },
            )
            PdfToolbar(
                tool = state.tool, onTool = vm::setTool,
                color = state.colorArgb, onColor = vm::setColor,
                areaColor = areaColor, onAreaColor = { areaColor = it }, meanings = meanings,
                canUndo = state.canUndo, canRedo = state.canRedo, onUndo = vm::undo, onRedo = vm::redo,
                onZoomIn = { zoom = (zoom * 1.25f).coerceAtMost(4f) }, onZoomOut = { zoom = (zoom / 1.25f).coerceAtLeast(1f) },
            )
            PdfPages(
                session = session,
                listState = listState,
                zoom = zoom,
                onZoom = { zoom = it },
                tool = state.tool,
                stylusOnly = settings.stylusOnlyDrawing,
                strokes = strokes,
                highlights = highlights,
                inkColor = state.colorArgb,
                inkWidth = state.widthFraction,
                onStroke = vm::addStroke,
                onErase = vm::eraseAt,
                onArea = { page, rect -> vm.highlightArea(page, rect, areaColor) },
            )
        }
        state.exportProgress?.let { ProgressBanner("Saving a copy · $it", Modifier.align(Alignment.BottomCenter)) }
        state.busy?.let { ProgressBanner(it, Modifier.align(Alignment.BottomCenter)) }
        if (state.tool != PdfTool.READ && state.exportProgress == null && state.busy == null) {
            Text(
                "Two fingers to scroll and zoom",
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).background(Color(0xCC14181F), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
        EinkFlash(flash)
    }

    if (showSheet) {
        PdfAnnotationsSheet(
            bookmarks = bookmarks.map { it.position to it },
            highlights = highlights,
            meanings = meanings,
            onGo = { page -> showSheet = false; scope.launch { listState.scrollToItem(page) } },
            onDeleteBookmark = { vm.deleteBookmark(it) },
            onDeleteHighlight = vm::deleteHighlight,
            onCite = { citeFor = it },
            onQuote = { onQuoteCard(it.id) },
            onNote = { h -> scope.launch { noteFor = h to vm.noteTextFor(h) } },
            onDismiss = { showSheet = false },
        )
    }
    citeFor?.let { h ->
        state.book?.let { book ->
            CitationSheet(book, h.text, (h.position + 1).toString(), settings.citationStyle, onStyleChange = {}, onDismiss = { citeFor = null })
        }
    }
    noteFor?.let { (h, text) -> QuickNoteDialog("Note on page ${h.position + 1}", text, onSave = { vm.saveNoteForHighlight(h, it) }, onDismiss = { noteFor = null }) }
    if (goTo) GoToPageDialog(session.pageCount, onGo = { scope.launch { listState.scrollToItem(it) } }, onDismiss = { goTo = false })
}

@Composable
private fun PdfToolbar(
    tool: PdfTool, onTool: (PdfTool) -> Unit,
    color: Int, onColor: (Int) -> Unit,
    areaColor: HighlightColor, onAreaColor: (HighlightColor) -> Unit, meanings: Map<HighlightColor, String>,
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit,
    onZoomIn: () -> Unit, onZoomOut: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PdfTool.entries.forEach { t -> FilterChip(selected = tool == t, onClick = { onTool(t) }, label = { Text(t.label) }) }
        Spacer(Modifier.width(4.dp))
        when (tool) {
            PdfTool.PEN -> InkPalette.forEach { c ->
                GlossyOrb(Color(c), Modifier.size(30.dp).clickable { onColor(c.toInt()) }, selected = color == c.toInt())
            }
            PdfTool.AREA -> HighlightColorRow(areaColor, meanings, onAreaColor)
            else -> Unit
        }
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
        IconButton(onClick = onZoomOut) { Icon(Icons.Filled.ZoomOut, "Zoom out") }
        IconButton(onClick = onZoomIn) { Icon(Icons.Filled.ZoomIn, "Zoom in") }
    }
}

@Composable
private fun PdfPages(
    session: PdfDocumentSession,
    listState: LazyListState,
    zoom: Float,
    onZoom: (Float) -> Unit,
    tool: PdfTool,
    stylusOnly: Boolean,
    strokes: Map<Int, List<InkStrokeEntity>>,
    highlights: List<HighlightEntity>,
    inkColor: Int,
    inkWidth: Float,
    onStroke: (Int, List<InkPoint>) -> Unit,
    onErase: (Int, InkPoint) -> Unit,
    onArea: (Int, RectF) -> Unit,
) {
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    val highlightsByPage = remember(highlights) { highlights.groupBy { it.position } }
    val currentZoom by androidx.compose.runtime.rememberUpdatedState(zoom)
    val zoomTo by androidx.compose.runtime.rememberUpdatedState(onZoom)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(if (LocalKarStyle.current.eink) Color.White else Color(0xFF3A3F48))
            .pointerInput(Unit) {
                // Two fingers: zoom and pan. Caught before children so drawing tools never see them.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val z = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newZoom = (currentZoom * z).coerceIn(1f, 4f)
                            if (newZoom != currentZoom) zoomTo(newZoom)
                            listState.dispatchRawDelta(-pan.y)
                            hScroll.dispatchRawDelta(-pan.x)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val viewport = maxWidth
        val pageWidth: Dp = (viewport - 24.dp) * zoom
        val renderPx = with(density) { (pageWidth.toPx() / 200f).toInt().coerceAtLeast(1) * 200 }
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            LazyColumn(
                state = listState,
                userScrollEnabled = tool == PdfTool.READ,
                contentPadding = PaddingValues(vertical = 16.dp, horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.width(pageWidth + 24.dp).fillMaxSize(),
            ) {
                items(session.pageCount, key = { it }) { index ->
                    PdfPage(
                        session = session, index = index, width = pageWidth, renderPx = renderPx,
                        strokes = strokes[index].orEmpty(), highlights = highlightsByPage[index].orEmpty(),
                        tool = tool, stylusOnly = stylusOnly, inkColor = inkColor, inkWidth = inkWidth,
                        onStroke = { onStroke(index, it) }, onErase = { onErase(index, it) }, onArea = { onArea(index, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPage(
    session: PdfDocumentSession,
    index: Int,
    width: Dp,
    renderPx: Int,
    strokes: List<InkStrokeEntity>,
    highlights: List<HighlightEntity>,
    tool: PdfTool,
    stylusOnly: Boolean,
    inkColor: Int,
    inkWidth: Float,
    onStroke: (List<InkPoint>) -> Unit,
    onErase: (InkPoint) -> Unit,
    onArea: (RectF) -> Unit,
) {
    val size = session.pageSizes[index]
    val aspect = size.width.toFloat() / size.height.coerceAtLeast(1)
    val bitmap by produceState<Bitmap?>(null, index, renderPx) { value = session.render(index, renderPx) }
    val live = remember { mutableStateListOf<Offset>() }
    val strokeCb by androidx.compose.runtime.rememberUpdatedState(onStroke)
    val eraseCb by androidx.compose.runtime.rememberUpdatedState(onErase)
    val areaCb by androidx.compose.runtime.rememberUpdatedState(onArea)
    var areaStart by remember { mutableStateOf<Offset?>(null) }
    var areaEnd by remember { mutableStateOf<Offset?>(null) }
    val glossy = LocalKarStyle.current.glossy
    Box(
        Modifier
            .width(width)
            .aspectRatio(aspect)
            .then(if (glossy) Modifier.shadow(10.dp) else Modifier)
            .background(Color.White),
    ) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize()) }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(tool, stylusOnly, inkColor, inkWidth) {
                    if (tool == PdfTool.READ) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val stylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                        if (stylusOnly && !stylus) return@awaitEachGesture // palm and finger rejection
                        val erasing = tool == PdfTool.ERASER || down.type == PointerType.Eraser
                        fun norm(o: Offset) = InkPoint(o.x / this.size.width, o.y / this.size.height)
                        down.consume()
                        live.clear()
                        if (tool == PdfTool.AREA) { areaStart = down.position; areaEnd = down.position }
                        else if (erasing) eraseCb(norm(down.position)) else live.add(down.position)
                        var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1 || event.changes.any { it.isConsumed }) { cancelled = true; break }
                            val change = event.changes.first()
                            if (!change.pressed) break
                            when {
                                tool == PdfTool.AREA -> areaEnd = change.position
                                erasing -> eraseCb(norm(change.position))
                                else -> live.add(change.position)
                            }
                            change.consume()
                        }
                        if (!cancelled) {
                            if (tool == PdfTool.AREA) {
                                val a = areaStart; val b = areaEnd
                                if (a != null && b != null) {
                                    val r = RectF(minOf(a.x, b.x) / this.size.width, minOf(a.y, b.y) / this.size.height, maxOf(a.x, b.x) / this.size.width, maxOf(a.y, b.y) / this.size.height)
                                    areaCb(r)
                                }
                            } else if (!erasing && live.isNotEmpty()) {
                                strokeCb(live.map { norm(it) })
                            }
                        }
                        live.clear(); areaStart = null; areaEnd = null
                    }
                },
        ) {
            highlights.forEach { h ->
                PdfExporter.parseRect(h.locator)?.let { r ->
                    drawRect(Color(HighlightColor.fromKey(h.colorKey).argb).copy(alpha = 0.38f), Offset(r.left * this.size.width, r.top * this.size.height), Size(r.width() * this.size.width, r.height() * this.size.height))
                }
            }
            strokes.forEach { drawInk(it) }
            if (live.size > 1) {
                val path = Path().apply { moveTo(live[0].x, live[0].y); for (i in 1 until live.size) lineTo(live[i].x, live[i].y) }
                val highlighter = tool == PdfTool.HIGHLIGHTER
                drawPath(
                    path,
                    if (highlighter) Color(0xFFF2C744).copy(alpha = 0.43f) else Color(inkColor),
                    style = Stroke(width = (if (highlighter) inkWidth * 5 else inkWidth) * this.size.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            val a = areaStart; val b = areaEnd
            if (a != null && b != null) {
                drawRect(Color(0x55F2C744), Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)))
                drawRect(Color(0xFFE0A33A), Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

private fun DrawScope.drawInk(stroke: InkStrokeEntity) {
    val pts = InkCodec.decode(stroke.points)
    if (pts.isEmpty()) return
    val path = Path().apply {
        moveTo(pts[0].x * size.width, pts[0].y * size.height)
        for (i in 1 until pts.size) lineTo(pts[i].x * size.width, pts[i].y * size.height)
        if (pts.size == 1) lineTo(pts[0].x * size.width + 0.5f, pts[0].y * size.height)
    }
    val highlighter = stroke.tool == "HIGHLIGHTER"
    drawPath(
        path,
        Color(stroke.colorArgb).copy(alpha = if (highlighter) 0.43f else 1f),
        style = Stroke(width = (stroke.widthFraction * size.width).coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** E-ink "full refresh": a brief black then white frame clears ghosting on e-ink screens. */
@Composable
fun EinkFlash(trigger: Int) {
    var color by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        color = Color.Black; delay(90); color = Color.White; delay(90); color = null
    }
    color?.let { Box(Modifier.fillMaxSize().background(it)) }
}

@Composable
fun ProgressBanner(text: String, modifier: Modifier = Modifier) {
    GlassCard(modifier.padding(16.dp).fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Text(text)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun PdfAnnotationsSheet(
    bookmarks: List<Pair<Int, com.kar.reader.data.db.BookmarkEntity>>,
    highlights: List<HighlightEntity>,
    meanings: Map<HighlightColor, String>,
    onGo: (Int) -> Unit,
    onDeleteBookmark: (com.kar.reader.data.db.BookmarkEntity) -> Unit,
    onDeleteHighlight: (HighlightEntity) -> Unit,
    onCite: (HighlightEntity) -> Unit,
    onQuote: (HighlightEntity) -> Unit,
    onNote: (HighlightEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Highlights · ${highlights.size}") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Bookmarks · ${bookmarks.size}") })
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(480.dp)) {
            if (tab == 0) {
                if (highlights.isEmpty()) item { Text("Choose “Highlight text” and drag over a passage. The text is read on this device so you can cite and review it.") }
                items(highlights, key = { it.id }) { h ->
                    HighlightCard(h, "Page ${h.position + 1}", meanings, onGo = { onGo(h.position) }, onCite = { onCite(h) }, onQuote = { onQuote(h) }, onNote = { onNote(h) }, onDelete = { onDeleteHighlight(h) })
                }
            } else {
                if (bookmarks.isEmpty()) item { Text("No bookmarks yet. Use the bookmark button in the top bar.") }
                items(bookmarks, key = { it.second.id }) { (page, b) ->
                    Row(Modifier.fillMaxWidth().clickable { onGo(page) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Text(b.label, Modifier.weight(1f))
                        TextButton(onClick = { onDeleteBookmark(b) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
fun HighlightCard(
    h: HighlightEntity,
    location: String,
    meanings: Map<HighlightColor, String>,
    onGo: (() -> Unit)?,
    onCite: () -> Unit,
    onQuote: () -> Unit,
    onNote: () -> Unit,
    onDelete: () -> Unit,
) {
    val color = HighlightColor.fromKey(h.colorKey)
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 6.dp, height = 18.dp).background(Color(color.argb), androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(8.dp))
            Text("${(meanings[color] ?: color.defaultMeaning).uppercase()} · $location", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(6.dp))
        Text(h.text, fontFamily = FontFamily.Serif, fontSize = 15.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (onGo != null) TextButton(onClick = onGo) { Text("Go to") }
            TextButton(onClick = onNote) { Text("Note") }
            TextButton(onClick = onCite) { Text("Cite") }
            TextButton(onClick = onQuote) { Text("Quote card") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val page = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                text, { text = it.filter(Char::isDigit).take(6) }, singleLine = true, label = { Text("Page (1–$pageCount)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { onGo(page!! - 1); onDismiss() }, enabled = page != null && page in 1..pageCount) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
