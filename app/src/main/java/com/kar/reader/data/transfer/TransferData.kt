package com.kar.reader.data.transfer

import androidx.room.withTransaction
import com.kar.reader.core.domain.SyncMerge
import com.kar.reader.data.db.BookEntity
import com.kar.reader.data.db.BookStateEntity
import com.kar.reader.data.db.BookTagEntity
import com.kar.reader.data.db.BookmarkEntity
import com.kar.reader.data.db.CollectionBookEntity
import com.kar.reader.data.db.CollectionEntity
import com.kar.reader.data.db.ColorMeaningEntity
import com.kar.reader.data.db.FlashcardEntity
import com.kar.reader.data.db.HighlightEntity
import com.kar.reader.data.db.InkStrokeEntity
import com.kar.reader.data.db.KarDatabase
import com.kar.reader.data.db.NoteEntity
import com.kar.reader.data.db.NoteVersionEntity
import com.kar.reader.data.db.NotebookEntity
import com.kar.reader.data.db.NotebookPageEntity
import com.kar.reader.data.db.NotebookStrokeEntity
import com.kar.reader.data.db.PageTextEntity
import com.kar.reader.data.db.ProfileEntity
import com.kar.reader.data.db.ReaderPreferencesEntity
import com.kar.reader.data.db.ReadingProgressEntity
import com.kar.reader.data.db.ReadingSessionEntity
import com.kar.reader.data.db.TagEntity
import com.kar.reader.data.db.VocabularyEntity
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the user created, in one serializable bundle. Used by backups and sync snapshots. */
@Serializable
data class TransferData(
    val formatVersion: Int = FORMAT_VERSION,
    val createdAt: Long,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val books: List<BookEntity> = emptyList(),
    val profiles: List<ProfileEntity> = emptyList(),
    val states: List<BookStateEntity> = emptyList(),
    val progress: List<ReadingProgressEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val highlights: List<HighlightEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val noteVersions: List<NoteVersionEntity> = emptyList(),
    val ink: List<InkStrokeEntity> = emptyList(),
    val readerPreferences: List<ReaderPreferencesEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val bookTags: List<BookTagEntity> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val collectionBooks: List<CollectionBookEntity> = emptyList(),
    val colorMeanings: List<ColorMeaningEntity> = emptyList(),
    val flashcards: List<FlashcardEntity> = emptyList(),
    val vocabulary: List<VocabularyEntity> = emptyList(),
    val sessions: List<ReadingSessionEntity> = emptyList(),
    val pageText: List<PageTextEntity> = emptyList(),
    val notebooks: List<NotebookEntity> = emptyList(),
    val notebookPages: List<NotebookPageEntity> = emptyList(),
    val notebookStrokes: List<NotebookStrokeEntity> = emptyList(),
) {
    fun counts(): Map<String, Int> = linkedMapOf(
        "books" to books.size, "profiles" to profiles.size, "progress" to progress.size, "bookmarks" to bookmarks.size,
        "highlights" to highlights.size, "notes" to notes.size, "noteVersions" to noteVersions.size, "ink" to ink.size,
        "tags" to tags.size, "collections" to collections.size, "flashcards" to flashcards.size, "vocabulary" to vocabulary.size,
        "sessions" to sessions.size, "notebooks" to notebooks.size, "notebookStrokes" to notebookStrokes.size,
    )

    companion object {
        const val FORMAT_VERSION = 1
    }
}

data class MergeReport(
    val added: Int,
    val updated: Int,
    val keptAsHistory: Int,
    val missingBooks: List<String>,
)

/**
 * Reads all user data into a [TransferData] and merges one back in. Merging is additive: rows are
 * matched by id, books by content hash, the newer change wins, and when two versions of a note differ
 * the older text is kept in note history. The whole merge runs in one transaction, so a failure leaves
 * the database exactly as it was.
 */
@Singleton
class TransferEngine @Inject constructor(private val db: KarDatabase) {

    /** Collects all data, or only one profile's data (for sync) when [profileId] is set. */
    suspend fun collect(profileId: String?, deviceId: String?, deviceName: String?): TransferData {
        fun <T> List<T>.forProfile(owner: (T) -> String): List<T> = if (profileId == null) this else filter { owner(it) == profileId }
        val notes = db.noteDao().getAll().forProfile { it.profileId }
        val noteIds = notes.map { it.id }.toSet()
        val notebooks = db.notebookDao().getAll().forProfile { it.profileId }
        val notebookIds = notebooks.map { it.id }.toSet()
        val pages = db.notebookDao().getAllPages().filter { it.notebookId in notebookIds }
        val pageIds = pages.map { it.id }.toSet()
        val collections = db.collectionDao().getAll().forProfile { it.profileId }
        val collectionIds = collections.map { it.id }.toSet()
        return TransferData(
            createdAt = System.currentTimeMillis(),
            deviceId = deviceId,
            deviceName = deviceName,
            books = db.bookDao().getAll(),
            profiles = db.profileDao().getAll().let { all -> if (profileId == null) all else all.filter { it.id == profileId } },
            states = db.bookStateDao().getAll().forProfile { it.profileId },
            progress = db.progressDao().getAll().forProfile { it.profileId },
            bookmarks = db.bookmarkDao().getAll().forProfile { it.profileId },
            highlights = db.highlightDao().getAll().forProfile { it.profileId },
            notes = notes,
            noteVersions = db.noteVersionDao().getAll().filter { it.noteId in noteIds },
            ink = db.inkDao().getAll().forProfile { it.profileId },
            readerPreferences = db.readerPreferencesDao().getAll().forProfile { it.profileId },
            tags = db.tagDao().getAll(),
            bookTags = db.tagDao().getAllLinks(),
            collections = collections,
            collectionBooks = db.collectionDao().getAllMembers().filter { it.collectionId in collectionIds },
            colorMeanings = db.colorMeaningDao().getAll().forProfile { it.profileId },
            flashcards = db.flashcardDao().getAll().forProfile { it.profileId },
            vocabulary = db.vocabularyDao().getAll().forProfile { it.profileId },
            sessions = db.sessionDao().getAll().forProfile { it.profileId },
            pageText = db.pageTextDao().getAll(),
            notebooks = notebooks,
            notebookPages = pages,
            notebookStrokes = db.notebookDao().getAllStrokes().filter { it.pageId in pageIds },
        )
    }

    /**
     * Merges [data] into the database.
     * @param profileOverride for sync: remap every row to this local profile.
     * @param resolveMissingBook called for books whose hash isn't in the library; return a stored file name
     *        to add the book (backup with files), or null to skip it and its rows.
     * @param clearUserDataFirst "replace" restore: remove existing notes, highlights, progress and other user data first
     *        (book files and library entries are kept).
     */
    suspend fun merge(
        data: TransferData,
        profileOverride: String?,
        resolveMissingBook: suspend (BookEntity) -> String?,
        clearUserDataFirst: Boolean,
        historyOrigin: String,
    ): MergeReport {
        require(data.formatVersion <= TransferData.FORMAT_VERSION) { "This file was made by a newer version of KAR READER" }
        var added = 0
        var updated = 0
        var history = 0
        val missing = mutableListOf<String>()

        db.withTransaction {
            if (clearUserDataFirst) {
                val sql = db.openHelper.writableDatabase
                listOf(
                    "notebook_strokes", "notebook_pages", "notebooks", "page_text", "reading_sessions", "vocabulary", "flashcards",
                    "color_meanings", "collection_books", "collections", "book_tags", "reader_preferences", "ink_strokes",
                    "note_versions", "notes", "highlights", "bookmarks", "reading_progress", "book_states",
                ).forEach { sql.execSQL("DELETE FROM $it") }
            }

            // Profiles.
            if (profileOverride == null) data.profiles.forEach { if (db.profileDao().insert(it) != -1L) added++ }
            fun pid(original: String) = profileOverride ?: original

            // Books: match by content hash; map backup ids to local ids.
            val bookIdMap = HashMap<String, String>()
            for (book in data.books) {
                val local = db.bookDao().findByHash(book.contentHash)
                if (local != null) {
                    bookIdMap[book.id] = local.id
                    if (book.modifiedAt > local.modifiedAt) {
                        db.bookDao().update(
                            local.copy(
                                userTitle = book.userTitle, userAuthor = book.userAuthor, year = book.year, publisher = book.publisher,
                                seriesName = book.seriesName, seriesIndex = book.seriesIndex, rating = book.rating, modifiedAt = book.modifiedAt,
                            ),
                        )
                        updated++
                    }
                    continue
                }
                val storedName = resolveMissingBook(book)
                if (storedName == null) {
                    if (book.deletedAt == null) missing += (book.userTitle ?: book.title)
                    continue
                }
                val id = if (db.bookDao().get(book.id) == null) book.id else UUID.randomUUID().toString()
                db.bookDao().insert(book.copy(id = id, storedFileName = storedName))
                bookIdMap[book.id] = id
                added++
            }
            fun bid(original: String?): String? = original?.let { bookIdMap[it] }

            data.states.forEach { s ->
                val b = bid(s.bookId) ?: return@forEach
                val local = db.bookStateDao().get(pid(s.profileId), b)
                if (local == null || s.modifiedAt > local.modifiedAt) { db.bookStateDao().upsert(s.copy(profileId = pid(s.profileId), bookId = b)); updated++ }
            }
            data.progress.forEach { p ->
                val b = bid(p.bookId) ?: return@forEach
                val local = db.progressDao().get(pid(p.profileId), b)
                if (local == null || p.updatedAt > local.updatedAt) { db.progressDao().upsert(p.copy(profileId = pid(p.profileId), bookId = b)); updated++ }
            }
            data.readerPreferences.forEach { r -> bid(r.bookId)?.let { db.readerPreferencesDao().upsert(r.copy(profileId = pid(r.profileId), bookId = it)) } }

            // Id-keyed rows: newest change wins.
            val localBookmarks = db.bookmarkDao().getAll()
            SyncMerge.lastWriterWins(localBookmarks, data.bookmarks).upserts.forEach { r ->
                bid(r.bookId)?.let { db.bookmarkDao().upsert(r.copy(profileId = pid(r.profileId), bookId = it)); added++ }
            }
            SyncMerge.lastWriterWins(db.highlightDao().getAll(), data.highlights).upserts.forEach { r ->
                bid(r.bookId)?.let { db.highlightDao().upsert(r.copy(profileId = pid(r.profileId), bookId = it)); added++ }
            }
            SyncMerge.lastWriterWins(db.inkDao().getAll(), data.ink).upserts.forEach { r ->
                bid(r.bookId)?.let { db.inkDao().upsert(r.copy(profileId = pid(r.profileId), bookId = it)); added++ }
            }

            // Notes: keep both versions when content differs.
            val notesMerge = SyncMerge.withConflicts(db.noteDao().getAll(), data.notes) { a, b -> a.title == b.title && a.body == b.body }
            notesMerge.upserts.forEach { n ->
                if (n.bookId != null && bid(n.bookId) == null) return@forEach
                db.noteDao().upsert(n.copy(profileId = pid(n.profileId), bookId = bid(n.bookId)))
                added++
            }
            notesMerge.conflicts.forEach { (_, loser) ->
                if (db.noteDao().get(loser.id) == null) return@forEach
                db.noteVersionDao().insert(NoteVersionEntity(UUID.randomUUID().toString(), loser.id, loser.title, loser.body, loser.modifiedAt, historyOrigin))
                history++
            }
            data.noteVersions.forEach { v -> if (db.noteDao().get(v.noteId) != null) db.noteVersionDao().insert(v) }

            // Tags by name.
            val tagIdMap = HashMap<String, String>()
            data.tags.forEach { t ->
                val local = db.tagDao().findByName(t.name)
                if (local != null) tagIdMap[t.id] = local.id else { db.tagDao().insert(t); tagIdMap[t.id] = db.tagDao().findByName(t.name)?.id ?: t.id }
            }
            data.bookTags.forEach { link ->
                val b = bid(link.bookId) ?: return@forEach
                val t = tagIdMap[link.tagId] ?: return@forEach
                db.tagDao().addBookTag(BookTagEntity(b, t))
            }

            SyncMerge.lastWriterWins(db.collectionDao().getAll(), data.collections).upserts.forEach { c -> db.collectionDao().upsert(c.copy(profileId = pid(c.profileId))) }
            data.collectionBooks.forEach { m ->
                val b = bid(m.bookId) ?: return@forEach
                if (db.collectionDao().get(m.collectionId) != null) db.collectionDao().addMember(CollectionBookEntity(m.collectionId, b))
            }
            data.colorMeanings.forEach { db.colorMeaningDao().upsert(it.copy(profileId = pid(it.profileId))) }

            SyncMerge.lastWriterWins(db.flashcardDao().getAll(), data.flashcards).upserts.forEach { c ->
                db.flashcardDao().upsert(c.copy(profileId = pid(c.profileId), bookId = c.bookId?.let { bid(it) }))
            }
            SyncMerge.lastWriterWins(db.vocabularyDao().getAll(), data.vocabulary).upserts.forEach { v ->
                db.vocabularyDao().upsert(v.copy(profileId = pid(v.profileId), bookId = v.bookId?.let { bid(it) }))
            }
            data.sessions.forEach { s -> if (db.sessionDao().insert(s.copy(profileId = pid(s.profileId), bookId = s.bookId?.let { bid(it) })) != -1L) added++ }
            db.pageTextDao().insertAll(data.pageText.mapNotNull { pt -> bid(pt.bookId)?.let { pt.copy(bookId = it) } })

            SyncMerge.lastWriterWins(db.notebookDao().getAll(), data.notebooks).upserts.forEach { db.notebookDao().upsert(it.copy(profileId = pid(it.profileId))) }
            val notebookIds = db.notebookDao().getAll().map { it.id }.toSet()
            val localPages = db.notebookDao().getAllPages().associateBy { it.id }
            data.notebookPages.forEach { p ->
                if (p.notebookId !in notebookIds) return@forEach
                val l = localPages[p.id]
                if (l == null || p.modifiedAt > l.modifiedAt) db.notebookDao().upsertPage(p)
            }
            val pageIds = db.notebookDao().getAllPages().map { it.id }.toSet()
            val localStrokes = db.notebookDao().getAllStrokes().associateBy { it.id }
            data.notebookStrokes.forEach { s ->
                if (s.pageId !in pageIds) return@forEach
                val l = localStrokes[s.id]
                if (l == null || s.modifiedAt > l.modifiedAt) db.notebookDao().upsertStroke(s)
            }
        }
        return MergeReport(added, updated, history, missing.distinct())
    }
}
