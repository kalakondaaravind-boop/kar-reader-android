@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kar.reader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.domain.InkCodec
import com.kar.reader.core.domain.InkGeometry
import com.kar.reader.core.domain.InkPoint
import com.kar.reader.core.input.PageTurnBus
import com.kar.reader.core.model.HighlightColor
import com.kar.reader.core.model.InkTool
import com.kar.reader.core.ocr.OnDeviceOcr
import com.kar.reader.data.db.BookEntity
import com.kar.reader.data.db.BookmarkEntity
import com.kar.reader.data.db.HighlightEntity
import com.kar.reader.data.db.InkStrokeEntity
import com.kar.reader.data.files.DocumentStore
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.prefs.AppSettings
import com.kar.reader.data.repo.AnnotationRepository
import com.kar.reader.data.repo.LibraryRepository
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.data.repo.ReadingRepository
import com.kar.reader.feature.pdf.PdfDocumentSession
import com.kar.reader.feature.pdf.PdfExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class PdfTool(val label: String) { READ("Read"), PEN("Pen"), HIGHLIGHTER("Highlighter"), ERASER("Eraser"), AREA("Highlight text") }

private sealed interface InkCommand {
    data class Added(val stroke: InkStrokeEntity) : InkCommand
    data class Removed(val strokes: List<InkStrokeEntity>) : InkCommand
}

data class PdfUiState(
    val book: BookEntity? = null,
    val session: PdfDocumentSession? = null,
    val error: String? = null,
    val initialPage: Int = 0,
    val initialOffset: Float = 0f,
    val tool: PdfTool = PdfTool.READ,
    val colorArgb: Int = 0xFFC0392B.toInt(),
    val widthFraction: Float = 0.004f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: String? = null,
    val exportProgress: String? = null,
    val busy: String? = null,
)

@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
    private val library: LibraryRepository,
    private val reading: ReadingRepository,
    private val annotations: AnnotationRepository,
    private val store: DocumentStore,
    prefs: AppPreferences,
    val pageTurns: PageTurnBus,
) : ViewModel() {

    val bookId: String = checkNotNull(savedState.get<String>("bookId"))

    private val _state = MutableStateFlow(PdfUiState())
    val state: StateFlow<PdfUiState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val strokes: StateFlow<Map<Int, List<InkStrokeEntity>>> = profiles.currentId.flatMapLatest { pid -> annotations.observeInk(pid, bookId) }
        .map { list -> list.groupBy { it.pageIndex } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val highlights: StateFlow<List<HighlightEntity>> = profiles.currentId.flatMapLatest { pid -> annotations.observeHighlights(pid, bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookmarks: StateFlow<List<BookmarkEntity>> = profiles.currentId.flatMapLatest { pid -> reading.observeBookmarks(pid, bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val meanings: StateFlow<Map<HighlightColor, String>> = profiles.currentId.flatMapLatest { annotations.observeMeanings(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HighlightColor.entries.associateWith { it.defaultMeaning })

    private val undo = ArrayDeque<InkCommand>()
    private val redo = ArrayDeque<InkCommand>()
    private var saveJob: Job? = null
    private var sessionStart = 0L

    init {
        viewModelScope.launch {
            val book = library.getBook(bookId)
            if (book == null || book.deletedAt != null) { _state.value = PdfUiState(error = "This book is no longer in your library."); return@launch }
            val file = library.fileFor(book)
            if (!file.isFile) { _state.value = PdfUiState(book = book, error = "The book's file is missing from app storage. Restore it from a backup or import it again."); return@launch }
            try {
                val session = PdfDocumentSession.open(file)
                val pid = profiles.currentIdNow()
                val progress = reading.getProgress(pid, bookId)
                library.markOpened(pid, bookId)
                _state.value = _state.value.copy(
                    book = book, session = session,
                    initialPage = (progress?.position ?: 0).coerceIn(0, (session.pageCount - 1).coerceAtLeast(0)),
                    initialOffset = progress?.progression ?: 0f,
                )
            } catch (e: Exception) {
                _state.value = PdfUiState(book = book, error = e.message ?: "This PDF can't be opened.")
            }
        }
    }

    // ---- Position and sessions ------------------------------------------------------------------

    fun onPosition(page: Int, offsetFraction: Float) {
        val count = _state.value.session?.pageCount ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            val total = if (count <= 1) 1f else (page + offsetFraction) / (count - 1).toFloat()
            reading.saveProgress(profiles.currentIdNow(), bookId, page, offsetFraction, total)
        }
    }

    fun onResume() { sessionStart = System.currentTimeMillis() }

    fun onPause() {
        val start = sessionStart
        if (start == 0L) return
        sessionStart = 0
        viewModelScope.launch { reading.recordSession(profiles.currentIdNow(), bookId, start, System.currentTimeMillis() - start, focus = false) }
    }

    // ---- Tools ----------------------------------------------------------------------------

    fun setTool(tool: PdfTool) { _state.value = _state.value.copy(tool = tool) }
    fun setColor(argb: Int) { _state.value = _state.value.copy(colorArgb = argb) }
    fun setWidth(fraction: Float) { _state.value = _state.value.copy(widthFraction = fraction) }
    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    fun addStroke(page: Int, points: List<InkPoint>) {
        val s = _state.value
        if (points.isEmpty()) return
        viewModelScope.launch {
            val highlighter = s.tool == PdfTool.HIGHLIGHTER
            val stroke = InkStrokeEntity(
                id = UUID.randomUUID().toString(),
                profileId = profiles.currentIdNow(),
                bookId = bookId,
                pageIndex = page,
                tool = if (highlighter) InkTool.HIGHLIGHTER.name else InkTool.PEN.name,
                colorArgb = if (highlighter) 0xFFF2C744.toInt() else s.colorArgb,
                widthFraction = if (highlighter) s.widthFraction * 5f else s.widthFraction,
                points = InkCodec.encode(points),
                documentHash = s.book?.contentHash.orEmpty(),
                createdAt = System.currentTimeMillis(),
            )
            annotations.insertInk(stroke)
            push(InkCommand.Added(stroke))
        }
    }

    /** Erases strokes on [page] passing within a small distance of [point] (normalized coordinates). */
    fun eraseAt(page: Int, point: InkPoint) {
        val candidates = strokes.value[page].orEmpty().filter { InkGeometry.distanceToStroke(point, InkCodec.decode(it.points)) < 0.015f }
        if (candidates.isEmpty()) return
        viewModelScope.launch {
            candidates.forEach { annotations.deleteInk(it.id) }
            push(InkCommand.Removed(candidates))
        }
    }

    private fun push(cmd: InkCommand) {
        undo.addLast(cmd)
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
        updateUndoState()
    }

    fun undo() {
        val cmd = undo.removeLastOrNull() ?: return
        viewModelScope.launch {
            when (cmd) {
                is InkCommand.Added -> annotations.deleteInk(cmd.stroke.id)
                is InkCommand.Removed -> cmd.strokes.forEach { annotations.insertInk(it) }
            }
            redo.addLast(cmd)
            updateUndoState()
        }
    }

    fun redo() {
        val cmd = redo.removeLastOrNull() ?: return
        viewModelScope.launch {
            when (cmd) {
                is InkCommand.Added -> annotations.insertInk(cmd.stroke)
                is InkCommand.Removed -> cmd.strokes.forEach { annotations.deleteInk(it.id) }
            }
            undo.addLast(cmd)
            updateUndoState()
        }
    }

    private fun updateUndoState() { _state.value = _state.value.copy(canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }

    /** Highlights a rectangular area and reads its text with on-device recognition, so it can be cited and studied. */
    fun highlightArea(page: Int, rect: RectF, color: HighlightColor) {
        val session = _state.value.session ?: return
        if (rect.width() < 0.01f || rect.height() < 0.005f) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "Reading the highlighted text…")
            val text = runCatching {
                val full = session.renderForExport(page, 1600)
                try {
                    val x = (rect.left * full.width).toInt().coerceIn(0, full.width - 1)
                    val y = (rect.top * full.height).toInt().coerceIn(0, full.height - 1)
                    val w = (rect.width() * full.width).toInt().coerceIn(1, full.width - x)
                    val h = (rect.height() * full.height).toInt().coerceIn(1, full.height - y)
                    val crop = Bitmap.createBitmap(full, x, y, w, h)
                    OnDeviceOcr.recognize(crop).text.replace(Regex("\\s+"), " ").trim().also { crop.recycle() }
                } finally {
                    full.recycle()
                }
            }.getOrDefault("")
            val label = text.ifBlank { "Highlighted area on page ${page + 1}" }
            annotations.addHighlight(profiles.currentIdNow(), bookId, page, PdfExporter.rectLocator(rect), label, color)
            _state.value = _state.value.copy(busy = null, message = if (text.isBlank()) "Highlighted. No printed text was recognized in that area." else "Highlighted: “${text.take(60)}”")
        }
    }

    fun deleteHighlight(h: HighlightEntity) = viewModelScope.launch { annotations.deleteHighlight(h.id) }

    fun saveNoteForHighlight(h: HighlightEntity, body: String) = viewModelScope.launch {
        val existing = annotations.noteForHighlight(h.id)
        annotations.saveNote(profiles.currentIdNow(), existing?.id, existing?.title ?: "Note on page ${h.position + 1}", body, bookId, h.id, h.position)
        _state.value = _state.value.copy(message = "Note saved")
    }

    suspend fun noteTextFor(h: HighlightEntity): String = annotations.noteForHighlight(h.id)?.body.orEmpty()

    // ---- Bookmarks --------------------------------------------------------------------------

    fun toggleBookmark(page: Int) = viewModelScope.launch {
        val existing = bookmarks.value.firstOrNull { it.position == page }
        if (existing != null) {
            reading.deleteBookmark(existing.id)
            _state.value = _state.value.copy(message = "Bookmark moved to the recycle bin")
        } else {
            reading.addBookmark(profiles.currentIdNow(), bookId, page, 0f, "Page ${page + 1}")
            _state.value = _state.value.copy(message = "Page ${page + 1} bookmarked")
        }
    }

    fun deleteBookmark(b: BookmarkEntity) = viewModelScope.launch { reading.deleteBookmark(b.id) }

    // ---- Export ---------------------------------------------------------------------------

    fun export(destination: Uri) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(exportProgress = "Preparing…")
                val pid = profiles.currentIdNow()
                PdfExporter.exportFlattened(
                    context, session, annotations.inkFor(pid, bookId), annotations.highlightsFor(pid, bookId), store.exportDir, destination,
                ) { done, total -> _state.value = _state.value.copy(exportProgress = "Page $done of $total") }
                _state.value = _state.value.copy(exportProgress = null, message = "Copy saved and verified. Your original PDF is unchanged.")
            } catch (e: Exception) {
                _state.value = _state.value.copy(exportProgress = null, message = "Export failed: ${e.message ?: "unknown error"}. Your original is unchanged.")
            }
        }
    }

    override fun onCleared() {
        _state.value.session?.close()
        super.onCleared()
    }
}
