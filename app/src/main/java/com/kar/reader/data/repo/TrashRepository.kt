package com.kar.reader.data.repo

import com.kar.reader.core.domain.TrashPolicy
import com.kar.reader.data.db.BookDao
import com.kar.reader.data.db.BookmarkDao
import com.kar.reader.data.db.FlashcardDao
import com.kar.reader.data.db.HighlightDao
import com.kar.reader.data.db.InkDao
import com.kar.reader.data.db.NoteDao
import com.kar.reader.data.db.NotebookDao
import com.kar.reader.data.db.VocabularyDao
import com.kar.reader.data.db.displayTitle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

enum class BinKind(val label: String) { BOOK("Book"), NOTE("Note"), HIGHLIGHT("Highlight"), BOOKMARK("Bookmark"), INK("Ink"), NOTEBOOK("Notebook"), WORD("Word") }

data class BinItem(val kind: BinKind, val id: String, val title: String, val detail: String, val deletedAt: Long) {
    fun daysLeft(now: Long): Int = TrashPolicy.daysLeft(deletedAt, now)
}

/** The recycle bin: everything the user deleted in the last 30 days, ready to restore. */
@Singleton
class TrashRepository @Inject constructor(
    private val bookDao: BookDao,
    private val noteDao: NoteDao,
    private val highlightDao: HighlightDao,
    private val bookmarkDao: BookmarkDao,
    private val inkDao: InkDao,
    private val notebookDao: NotebookDao,
    private val vocabDao: VocabularyDao,
    private val flashcardDao: FlashcardDao,
    private val library: LibraryRepository,
    private val annotations: AnnotationRepository,
    private val flashcards: FlashcardRepository,
) {

    fun observeBin(profileId: String): Flow<List<BinItem>> {
        val first = combine(
            bookDao.observeDeleted(),
            noteDao.observeDeleted(profileId),
            highlightDao.observeDeleted(profileId),
            bookmarkDao.observeDeleted(profileId),
        ) { books, notes, highlights, bookmarks ->
            books.map { BinItem(BinKind.BOOK, it.id, it.displayTitle, "${it.format} · notes, highlights and progress are kept with it", it.deletedAt ?: 0) } +
                notes.map { BinItem(BinKind.NOTE, it.id, it.title.ifBlank { "Untitled note" }, it.body.take(80), it.deletedAt ?: 0) } +
                highlights.map { BinItem(BinKind.HIGHLIGHT, it.id, "“${it.text.take(60)}”", "Highlight", it.deletedAt ?: 0) } +
                bookmarks.map { BinItem(BinKind.BOOKMARK, it.id, it.label, "Bookmark", it.deletedAt ?: 0) }
        }
        return combine(
            first,
            inkDao.observeDeleted(profileId),
            notebookDao.observeDeleted(profileId),
            vocabDao.observeDeleted(profileId),
        ) { items, ink, notebooks, words ->
            (
                items +
                    ink.map { BinItem(BinKind.INK, it.id, "Ink stroke", "Page ${it.pageIndex + 1}", it.deletedAt ?: 0) } +
                    notebooks.map { BinItem(BinKind.NOTEBOOK, it.id, it.title, "Notebook", it.deletedAt ?: 0) } +
                    words.map { BinItem(BinKind.WORD, it.id, it.word, "Saved word", it.deletedAt ?: 0) }
                ).sortedByDescending { it.deletedAt }
        }
    }

    suspend fun restore(item: BinItem) {
        val now = System.currentTimeMillis()
        when (item.kind) {
            BinKind.BOOK -> library.restoreBook(item.id)
            BinKind.NOTE -> annotations.restoreNote(item.id)
            BinKind.HIGHLIGHT -> annotations.restoreHighlight(item.id)
            BinKind.BOOKMARK -> bookmarkDao.restore(item.id, now)
            BinKind.INK -> annotations.restoreInk(item.id)
            BinKind.NOTEBOOK -> notebookDao.restore(item.id, now)
            BinKind.WORD -> flashcards.restoreWord(item.id)
        }
    }

    /** Removes items past their 30 days. Runs at app start and when the bin is opened. */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int = purgeBefore(TrashPolicy.purgeBefore(now))

    /** Empties the bin completely. The UI confirms this first. */
    suspend fun emptyNow(): Int = purgeBefore(System.currentTimeMillis() + 1)

    private suspend fun purgeBefore(cutoff: Long): Int {
        var count = 0
        bookDao.getDeletedBefore(cutoff).forEach { library.deleteForever(it); count++ }
        count += noteDao.purge(cutoff)
        count += highlightDao.purge(cutoff)
        count += bookmarkDao.purge(cutoff)
        count += inkDao.purge(cutoff)
        count += notebookDao.purge(cutoff)
        count += notebookDao.purgeStrokes(cutoff)
        count += vocabDao.purge(cutoff)
        flashcardDao.purge(cutoff)
        return count
    }
}
