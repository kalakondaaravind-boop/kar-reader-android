@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kar.reader.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.input.PageTurnBus
import com.kar.reader.core.model.HighlightColor
import com.kar.reader.data.db.BookEntity
import com.kar.reader.data.db.BookmarkEntity
import com.kar.reader.data.db.HighlightEntity
import com.kar.reader.data.db.ReaderPreferencesEntity
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.prefs.AppSettings
import com.kar.reader.data.repo.AnnotationRepository
import com.kar.reader.data.repo.FlashcardRepository
import com.kar.reader.data.repo.LibraryRepository
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.data.repo.ReadingRepository
import com.kar.reader.feature.epub.EpubSession
import com.kar.reader.feature.epub.ReaderTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/** A text selection reported by the reader's script: offsets into the chapter's text content. */
data class EpubSelection(val start: Int, val length: Int, val text: String)

data class EpubUiState(
    val book: BookEntity? = null,
    val session: EpubSession? = null,
    val error: String? = null,
    val chapter: Int = 0,
    /** Where to scroll after a chapter loads: a 0..1 progression, or a fragment id. */
    val pendingProgression: Float = 0f,
    val pendingFragment: String? = null,
    val loadToken: Int = 0,
    val message: String? = null,
)

@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val library: LibraryRepository,
    private val reading: ReadingRepository,
    private val annotations: AnnotationRepository,
    private val flashcards: FlashcardRepository,
    prefs: AppPreferences,
    val pageTurns: PageTurnBus,
) : ViewModel() {

    val bookId: String = checkNotNull(savedState.get<String>("bookId"))

    private val _state = MutableStateFlow(EpubUiState())
    val state: StateFlow<EpubUiState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val readerPrefs: StateFlow<ReaderPreferencesEntity?> = profiles.currentId.flatMapLatest { reading.observeReaderPreferences(it, bookId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val highlights: StateFlow<List<HighlightEntity>> = profiles.currentId.flatMapLatest { annotations.observeHighlights(it, bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookmarks: StateFlow<List<BookmarkEntity>> = profiles.currentId.flatMapLatest { reading.observeBookmarks(it, bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val meanings: StateFlow<Map<HighlightColor, String>> = profiles.currentId.flatMapLatest { annotations.observeMeanings(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HighlightColor.entries.associateWith { it.defaultMeaning })

    private var lastProgression = 0f
    private var saveJob: Job? = null
    private var sessionStart = 0L

    init {
        viewModelScope.launch {
            val book = library.getBook(bookId)
            if (book == null || book.deletedAt != null) { _state.value = EpubUiState(error = "This book is no longer in your library."); return@launch }
            val file = library.fileFor(book)
            if (!file.isFile) { _state.value = EpubUiState(book = book, error = "The book's file is missing from app storage. Restore it from a backup or import it again."); return@launch }
            try {
                val session = EpubSession.open(file)
                val pid = profiles.currentIdNow()
                val progress = reading.getProgress(pid, bookId)
                library.markOpened(pid, bookId)
                val chapter = (progress?.position ?: firstLinear(session)).coerceIn(0, session.spineHrefs.size - 1)
                lastProgression = progress?.progression ?: 0f
                _state.value = EpubUiState(book = book, session = session, chapter = chapter, pendingProgression = lastProgression, loadToken = 1)
            } catch (e: Exception) {
                _state.value = EpubUiState(book = book, error = "This EPUB can't be opened: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun firstLinear(session: EpubSession) = session.publication.spine.indexOfFirst { it.linear }.coerceAtLeast(0)

    fun goToChapter(index: Int, progression: Float = 0f, fragment: String? = null) {
        val s = _state.value
        val session = s.session ?: return
        if (index !in session.spineHrefs.indices) return
        lastProgression = progression
        _state.value = s.copy(chapter = index, pendingProgression = progression, pendingFragment = fragment, loadToken = s.loadToken + 1)
        persist()
    }

    /** Opens an internal link or table-of-contents target ("path/chapter.xhtml#id"). */
    fun goToHref(href: String) {
        val session = _state.value.session ?: return
        val index = session.publication.spineIndexOf(href)
        if (index >= 0) goToChapter(index, 0f, href.substringAfter('#', "").ifEmpty { null })
    }

    fun nextChapter(): Boolean {
        val s = _state.value
        val count = s.session?.spineHrefs?.size ?: return false
        if (s.chapter + 1 >= count) return false
        goToChapter(s.chapter + 1, 0f); return true
    }

    fun previousChapter(toEnd: Boolean = false): Boolean {
        val s = _state.value
        if (s.chapter <= 0) return false
        goToChapter(s.chapter - 1, if (toEnd) 1f else 0f); return true
    }

    /** Reloads the current chapter at the current position, e.g. after a theme change. */
    fun reloadHere() {
        val s = _state.value
        _state.value = s.copy(pendingProgression = lastProgression, pendingFragment = null, loadToken = s.loadToken + 1)
    }

    fun onProgression(p: Float) {
        if (kotlin.math.abs(p - lastProgression) < 0.002f) return
        lastProgression = p
        persist()
    }

    private fun persist() {
        val s = _state.value
        val count = s.session?.spineHrefs?.size ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700)
            val total = ((s.chapter + lastProgression) / count).coerceIn(0f, 1f)
            reading.saveProgress(profiles.currentIdNow(), bookId, s.chapter, lastProgression, total)
        }
    }

    fun currentProgression() = lastProgression

    fun onResume() { sessionStart = System.currentTimeMillis() }

    fun onPause() {
        val start = sessionStart
        if (start == 0L) return
        sessionStart = 0
        viewModelScope.launch { reading.recordSession(profiles.currentIdNow(), bookId, start, System.currentTimeMillis() - start, focus = false) }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
    private fun say(text: String) { _state.value = _state.value.copy(message = text) }

    // ---- Preferences ----------------------------------------------------------------------------

    fun updatePrefs(change: (ReaderPreferencesEntity) -> ReaderPreferencesEntity) = viewModelScope.launch {
        val current = readerPrefs.value ?: ReaderPreferencesEntity(profiles.currentIdNow(), bookId)
        reading.saveReaderPreferences(change(current))
    }

    fun effectiveTheme(prefs: ReaderPreferencesEntity?, eink: Boolean): ReaderTheme = if (eink) ReaderTheme.EINK else ReaderTheme.from(prefs?.theme)

    // ---- Selection actions ---------------------------------------------------------------------

    /** Parses the value returned by evaluateJavascript for __kar.selection(). */
    fun parseSelection(jsResult: String?): EpubSelection? = runCatching {
        if (jsResult == null || jsResult == "null") return null
        val inner = JSONArray("[$jsResult]").getString(0)
        val o = JSONObject(inner)
        EpubSelection(o.getInt("start"), o.getInt("length"), o.getString("text"))
    }.getOrNull()?.takeIf { it.length > 0 && it.text.isNotBlank() }

    fun locationLabel(): String = _state.value.session?.chapterLabel(_state.value.chapter) ?: ""

    fun addHighlight(sel: EpubSelection, color: HighlightColor, then: (HighlightEntity) -> Unit = {}) = viewModelScope.launch {
        val h = annotations.addHighlight(profiles.currentIdNow(), bookId, _state.value.chapter, "${sel.start}:${sel.length}", sel.text, color)
        say("Highlighted")
        then(h)
    }

    fun saveNoteForSelection(sel: EpubSelection, body: String) = viewModelScope.launch {
        val pid = profiles.currentIdNow()
        val h = annotations.addHighlight(pid, bookId, _state.value.chapter, "${sel.start}:${sel.length}", sel.text, HighlightColor.YELLOW)
        annotations.saveNote(pid, null, "Note · ${locationLabel()}", body, bookId, h.id, _state.value.chapter)
        say("Note saved")
    }

    fun saveNoteForHighlight(h: HighlightEntity, body: String) = viewModelScope.launch {
        val existing = annotations.noteForHighlight(h.id)
        annotations.saveNote(profiles.currentIdNow(), existing?.id, existing?.title ?: "Note · ${_state.value.session?.chapterLabel(h.position).orEmpty()}", body, bookId, h.id, h.position)
        say("Note saved")
    }

    suspend fun noteTextFor(h: HighlightEntity): String = annotations.noteForHighlight(h.id)?.body.orEmpty()

    fun saveWord(sel: EpubSelection) = viewModelScope.launch {
        val word = sel.text.trim().split(Regex("\\s+")).take(4).joinToString(" ")
        flashcards.saveWord(profiles.currentIdNow(), word, sel.text.take(200), bookId)
        say("“$word” saved to your words and flashcards")
    }

    fun deleteHighlight(h: HighlightEntity) = viewModelScope.launch { annotations.deleteHighlight(h.id) }

    fun toggleBookmark() = viewModelScope.launch {
        val s = _state.value
        val near = bookmarks.value.firstOrNull { it.position == s.chapter && kotlin.math.abs(it.progression - lastProgression) < 0.03f }
        if (near != null) { reading.deleteBookmark(near.id); say("Bookmark moved to the recycle bin") }
        else {
            reading.addBookmark(profiles.currentIdNow(), bookId, s.chapter, lastProgression, "${locationLabel()} · ${(lastProgression * 100).toInt()}%")
            say("Bookmarked")
        }
    }

    fun deleteBookmark(b: BookmarkEntity) = viewModelScope.launch { reading.deleteBookmark(b.id) }

    override fun onCleared() {
        _state.value.session?.close()
        super.onCleared()
    }
}
