@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kar.reader.ui.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.GlossyOrb
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.core.designsystem.LocalKarStyle
import com.kar.reader.core.domain.InkCodec
import com.kar.reader.core.domain.InkGeometry
import com.kar.reader.core.domain.InkPoint
import com.kar.reader.core.domain.ShapeRecognizer
import com.kar.reader.core.model.InkPalette
import com.kar.reader.data.db.NotebookEntity
import com.kar.reader.data.db.NotebookPageEntity
import com.kar.reader.data.db.NotebookStrokeEntity
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.prefs.AppSettings
import com.kar.reader.data.repo.NotebookRepository
import com.kar.reader.data.repo.PageTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class NotebookTool(val label: String) { PEN("Pen"), HIGHLIGHTER("Highlighter"), LASSO("Lasso"), ERASER("Eraser") }

/** Notebook pages use A4 proportions. Coordinates are fractions of page width for both axes. */
private const val PAGE_RATIO = 1.4142f

private sealed interface NbCommand {
    data class Added(val strokes: List<NotebookStrokeEntity>) : NbCommand
    data class Deleted(val strokes: List<NotebookStrokeEntity>) : NbCommand
    data class Replaced(val before: List<NotebookStrokeEntity>, val after: List<NotebookStrokeEntity>) : NbCommand
}

@HiltViewModel
class NotebookViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: NotebookRepository,
    private val prefs: AppPreferences,
) : ViewModel() {
    val notebookId: String = checkNotNull(savedState.get<String>("notebookId"))
    val notebook: StateFlow<NotebookEntity?> = repo.observeNotebook(notebookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pages: StateFlow<List<NotebookPageEntity>> = repo.observePages(notebookId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pageIndex = MutableStateFlow(0)
    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val currentPage: StateFlow<NotebookPageEntity?> = combine(pages, pageIndex) { p, i -> p.getOrNull(i.coerceIn(0, (p.size - 1).coerceAtLeast(0))) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val strokes: StateFlow<List<NotebookStrokeEntity>> = currentPage.flatMapLatest { page -> page?.let { repo.observeStrokes(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val undoStack = ArrayDeque<NbCommand>()
    val canUndo = MutableStateFlow(false)

    fun setStylusOnly(on: Boolean) = viewModelScope.launch { prefs.setStylusOnlyDrawing(on) }

    fun addStroke(points: List<InkPoint>, tool: NotebookTool, color: Int, width: Float, straighten: Boolean) {
        val page = currentPage.value ?: return
        val shaped = if (straighten && tool == NotebookTool.PEN) ShapeRecognizer.recognize(points)?.points ?: points else points
        val stroke = NotebookStrokeEntity(
            id = UUID.randomUUID().toString(), pageId = page.id,
            tool = if (tool == NotebookTool.HIGHLIGHTER) "HIGHLIGHTER" else "PEN",
            colorArgb = if (tool == NotebookTool.HIGHLIGHTER) 0xFFF2C744.toInt() else color,
            widthFraction = if (tool == NotebookTool.HIGHLIGHTER) width * 5 else width,
            points = InkCodec.encode(shaped), createdAt = System.currentTimeMillis(),
        )
        viewModelScope.launch { repo.saveStroke(stroke); push(NbCommand.Added(listOf(stroke))) }
    }

    fun eraseAt(p: InkPoint) {
        val hit = strokes.value.filter { InkGeometry.distanceToStroke(p, InkCodec.decode(it.points)) < 0.012f }
        if (hit.isEmpty()) return
        viewModelScope.launch { repo.deleteStrokes(hit.map { it.id }); push(NbCommand.Deleted(hit)) }
    }

    fun select(lasso: List<InkPoint>): Set<String> =
        strokes.value.filter { InkGeometry.lassoSelects(InkCodec.decode(it.points), lasso) }.map { it.id }.toSet()

    fun move(ids: Set<String>, dx: Float, dy: Float) = transform(ids) { s -> s.copy(points = InkCodec.encode(InkGeometry.translate(InkCodec.decode(s.points), dx, dy))) }

    fun recolor(ids: Set<String>, color: Int) = transform(ids) { s -> s.copy(colorArgb = color) }

    fun copy(ids: Set<String>) {
        val copies = strokes.value.filter { it.id in ids }.map {
            it.copy(id = UUID.randomUUID().toString(), points = InkCodec.encode(InkGeometry.translate(InkCodec.decode(it.points), 0.03f, 0.03f)), createdAt = System.currentTimeMillis())
        }
        viewModelScope.launch { copies.forEach { repo.saveStroke(it) }; push(NbCommand.Added(copies)) }
    }

    fun delete(ids: Set<String>) {
        val victims = strokes.value.filter { it.id in ids }
        viewModelScope.launch { repo.deleteStrokes(victims.map { it.id }); push(NbCommand.Deleted(victims)) }
    }

    private fun transform(ids: Set<String>, change: (NotebookStrokeEntity) -> NotebookStrokeEntity) {
        val before = strokes.value.filter { it.id in ids }
        val after = before.map(change)
        viewModelScope.launch { after.forEach { repo.saveStroke(it) }; push(NbCommand.Replaced(before, after)) }
    }

    private fun push(c: NbCommand) {
        undoStack.addLast(c)
        if (undoStack.size > 100) undoStack.removeFirst()
        canUndo.value = true
    }

    fun undo() {
        val c = undoStack.removeLastOrNull() ?: return
        canUndo.value = undoStack.isNotEmpty()
        viewModelScope.launch {
            when (c) {
                is NbCommand.Added -> repo.deleteStrokes(c.strokes.map { it.id })
                is NbCommand.Deleted -> c.strokes.forEach { repo.saveStroke(it) }
                is NbCommand.Replaced -> c.before.forEach { repo.saveStroke(it) }
            }
        }
    }

    fun addPage(template: PageTemplate) = viewModelScope.launch {
        repo.addPage(notebookId, template)
        pageIndex.value = pages.value.size // the new page arrives at the end
    }

    fun setTemplate(template: PageTemplate) = viewModelScope.launch { currentPage.value?.let { repo.setTemplate(it, template) } }

    fun rename(title: String) = viewModelScope.launch { notebook.value?.let { repo.rename(it, title) } }
}

@Composable
fun NotebookScreen(onBack: () -> Unit, vm: NotebookViewModel = hiltViewModel()) {
    val notebook by vm.notebook.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val page by vm.currentPage.collectAsStateWithLifecycle()
    val index by vm.pageIndex.collectAsStateWithLifecycle()
    val strokes by vm.strokes.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    var tool by rememberSaveable { mutableStateOf(NotebookTool.PEN) }
    var color by rememberSaveable { mutableStateOf(InkPalette[2].toInt()) }
    var straighten by rememberSaveable { mutableStateOf(true) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var templateMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        KarTopBar(
            title = notebook?.title ?: "Notebook",
            subtitle = "Page ${index + 1} of ${pages.size.coerceAtLeast(1)} · ${page?.template?.let { t -> PageTemplate.entries.firstOrNull { it.name == t }?.label } ?: ""}",
            onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack,
            actions = {
                IconButton(onClick = vm::undo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = { vm.pageIndex.value = (index - 1).coerceAtLeast(0); selection = emptySet() }, enabled = index > 0) { Icon(Icons.Filled.ChevronLeft, "Previous page") }
                IconButton(onClick = { vm.pageIndex.value = (index + 1).coerceAtMost(pages.size - 1); selection = emptySet() }, enabled = index < pages.size - 1) { Icon(Icons.Filled.ChevronRight, "Next page") }
                Box {
                    IconButton(onClick = { templateMenu = true }) { Icon(Icons.Filled.Add, "Add page or change template") }
                    DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                        PageTemplate.entries.forEach { t -> DropdownMenuItem(text = { Text("New ${t.label.lowercase()} page") }, onClick = { vm.addPage(t); templateMenu = false; selection = emptySet() }) }
                        PageTemplate.entries.forEach { t -> DropdownMenuItem(text = { Text("Make this page ${t.label.lowercase()}") }, onClick = { vm.setTemplate(t); templateMenu = false }) }
                    }
                }
            },
        )
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NotebookTool.entries.forEach { t -> FilterChip(selected = tool == t, onClick = { tool = t; if (t != NotebookTool.LASSO) selection = emptySet() }, label = { Text(t.label) }) }
            FilterChip(selected = straighten, onClick = { straighten = !straighten }, label = { Text("Straighten shapes") })
            FilterChip(selected = settings.stylusOnlyDrawing, onClick = { vm.setStylusOnly(!settings.stylusOnlyDrawing) }, label = { Text("Palm rejection") })
            InkPalette.forEach { c -> GlossyOrb(Color(c), Modifier.size(28.dp).clickable { color = c.toInt(); if (selection.isNotEmpty()) vm.recolor(selection, c.toInt()) }, selected = color == c.toInt()) }
        }
        if (selection.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${selection.size} stroke${if (selection.size == 1) "" else "s"} selected · drag inside the box to move", Modifier.weight(1f), fontSize = 13.sp)
                TextButton(onClick = { vm.copy(selection) }) { Text("Copy") }
                TextButton(onClick = { vm.delete(selection); selection = emptySet() }) { Text("Delete") }
                TextButton(onClick = { selection = emptySet() }) { Text("Done") }
            }
        }
        Box(Modifier.fillMaxSize().background(if (LocalKarStyle.current.eink) Color.White else Color(0xFF3A3F48)).verticalScroll(rememberScrollState(), enabled = false), contentAlignment = Alignment.TopCenter) {
            BoxWithConstraints(Modifier.padding(16.dp).widthIn(max = 900.dp).fillMaxWidth()) {
                NotebookCanvas(
                    template = page?.template ?: "BLANK",
                    strokes = strokes,
                    tool = tool,
                    color = color,
                    stylusOnly = settings.stylusOnlyDrawing,
                    selection = selection,
                    onStroke = { pts -> vm.addStroke(pts, tool, color, 0.0035f, straighten) },
                    onErase = vm::eraseAt,
                    onLasso = { selection = vm.select(it) },
                    onMove = { dx, dy -> vm.move(selection, dx, dy) },
                )
            }
        }
    }
}

@Composable
private fun NotebookCanvas(
    template: String,
    strokes: List<NotebookStrokeEntity>,
    tool: NotebookTool,
    color: Int,
    stylusOnly: Boolean,
    selection: Set<String>,
    onStroke: (List<InkPoint>) -> Unit,
    onErase: (InkPoint) -> Unit,
    onLasso: (List<InkPoint>) -> Unit,
    onMove: (Float, Float) -> Unit,
) {
    val live = remember { mutableStateListOf<Offset>() }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val currentSelection by rememberUpdatedState(selection)
    val currentStrokes by rememberUpdatedState(strokes)
    val strokeCb by rememberUpdatedState(onStroke)
    val eraseCb by rememberUpdatedState(onErase)
    val lassoCb by rememberUpdatedState(onLasso)
    val moveCb by rememberUpdatedState(onMove)
    val glossy = LocalKarStyle.current.glossy
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f / PAGE_RATIO)
            .then(if (glossy) Modifier.shadow(12.dp) else Modifier)
            .background(Color.White)
            .pointerInput(tool, stylusOnly, color) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val stylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                    if (stylusOnly && !stylus) return@awaitEachGesture
                    val w = size.width.toFloat()
                    fun norm(o: Offset) = InkPoint(o.x / w, o.y / w)
                    val erasing = tool == NotebookTool.ERASER || down.type == PointerType.Eraser
                    // Lasso: dragging inside the current selection moves it.
                    val selected = currentStrokes.filter { it.id in currentSelection }
                    val box = bounds(selected.flatMap { InkCodec.decode(it.points) })
                    val moving = tool == NotebookTool.LASSO && box != null && norm(down.position).let { p -> p.x in box[0]..box[2] && p.y in box[1]..box[3] }
                    val start = down.position
                    live.clear()
                    if (erasing) eraseCb(norm(down.position)) else if (!moving) live.add(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        when {
                            moving -> drag = ch.position - start
                            erasing -> eraseCb(norm(ch.position))
                            else -> live.add(ch.position)
                        }
                        ch.consume()
                    }
                    when {
                        moving -> { moveCb(drag.x / w, drag.y / w); drag = Offset.Zero }
                        erasing -> Unit
                        tool == NotebookTool.LASSO -> if (live.size > 3) lassoCb(live.map { norm(it) })
                        live.isNotEmpty() -> strokeCb(live.map { norm(it) })
                    }
                    live.clear()
                }
            },
    ) {
        drawTemplate(template)
        val w = size.width
        strokes.forEach { s ->
            val offset = if (s.id in selection) drag else Offset.Zero
            drawStrokePath(InkCodec.decode(s.points), w, offset, Color(s.colorArgb).copy(alpha = if (s.tool == "HIGHLIGHTER") 0.43f else 1f), s.widthFraction * w)
        }
        if (selection.isNotEmpty()) {
            bounds(strokes.filter { it.id in selection }.flatMap { InkCodec.decode(it.points) })?.let { b ->
                drawRect(
                    Color(0xFFE0A33A), Offset(b[0] * w + drag.x - 6, b[1] * w + drag.y - 6), Size((b[2] - b[0]) * w + 12, (b[3] - b[1]) * w + 12),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }
        }
        if (live.size > 1) {
            val path = Path().apply { moveTo(live[0].x, live[0].y); for (i in 1 until live.size) lineTo(live[i].x, live[i].y) }
            if (tool == NotebookTool.LASSO) {
                drawPath(path, Color(0xFFE0A33A), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
            } else {
                val hl = tool == NotebookTool.HIGHLIGHTER
                drawPath(path, if (hl) Color(0x6EF2C744) else Color(color), style = Stroke((if (hl) 0.0175f else 0.0035f) * w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

private fun bounds(points: List<InkPoint>): FloatArray? {
    if (points.isEmpty()) return null
    return floatArrayOf(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
}

private fun DrawScope.drawStrokePath(pts: List<InkPoint>, w: Float, offset: Offset, color: Color, width: Float) {
    if (pts.isEmpty()) return
    val path = Path().apply {
        moveTo(pts[0].x * w + offset.x, pts[0].y * w + offset.y)
        for (i in 1 until pts.size) lineTo(pts[i].x * w + offset.x, pts[i].y * w + offset.y)
        if (pts.size == 1) lineTo(pts[0].x * w + offset.x + 0.5f, pts[0].y * w + offset.y)
    }
    drawPath(path, color, style = Stroke(width.coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawTemplate(template: String) {
    val line = Color(0xFFDDE3EA)
    val step = size.width / 28f
    when (template) {
        "LINED" -> { var y = step * 3; while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1.5f); y += step } }
        "GRID" -> {
            var x = step; while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = step; while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += step }
        }
        "DOTTED" -> {
            var y = step; while (y < size.height) { var x = step; while (x < size.width) { drawCircle(Color(0xFFB8C0CB), 1.6f, Offset(x, y)); x += step }; y += step }
        }
        else -> Unit
    }
}

