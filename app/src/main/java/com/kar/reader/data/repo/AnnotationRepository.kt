package com.kar.reader.data.repo

import com.kar.reader.core.model.HighlightColor
import com.kar.reader.data.db.ColorMeaningDao
import com.kar.reader.data.db.ColorMeaningEntity
import com.kar.reader.data.db.HighlightDao
import com.kar.reader.data.db.HighlightEntity
import com.kar.reader.data.db.InkDao
import com.kar.reader.data.db.InkStrokeEntity
import com.kar.reader.data.db.NoteDao
import com.kar.reader.data.db.NoteEntity
import com.kar.reader.data.db.NoteVersionDao
import com.kar.reader.data.db.NoteVersionEntity
import com.kar.reader.data.prefs.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnotationRepository @Inject constructor(
    private val highlightDao: HighlightDao,
    private val noteDao: NoteDao,
    private val versionDao: NoteVersionDao,
    private val inkDao: InkDao,
    private val meaningDao: ColorMeaningDao,
    private val flashcards: FlashcardRepository,
    private val prefs: AppPreferences,
) {
    // ---- Highlights --------------------------------------------------------------------------

    fun observeHighlights(profileId: String, bookId: String): Flow<List<HighlightEntity>> = highlightDao.observeForBook(profileId, bookId)

    fun observeAllHighlights(profileId: String): Flow<List<HighlightEntity>> = highlightDao.observeForProfile(profileId)

    suspend fun highlightsFor(profileId: String, bookId: String): List<HighlightEntity> = highlightDao.getForBook(profileId, bookId)

    suspend fun addHighlight(profileId: String, bookId: String, position: Int, locator: String, text: String, color: HighlightColor): HighlightEntity {
        val h = HighlightEntity(UUID.randomUUID().toString(), profileId, bookId, position, locator, text.trim().take(5000), color.name, System.currentTimeMillis())
        highlightDao.insert(h)
        if (prefs.settings.first().autoFlashcards) flashcards.createFromHighlight(h)
        return h
    }

    suspend fun recolor(id: String, color: HighlightColor) = highlightDao.setColor(id, color.name, System.currentTimeMillis())

    suspend fun deleteHighlight(id: String) {
        val now = System.currentTimeMillis()
        highlightDao.softDelete(id, now)
        flashcards.softDeleteForSource(id, now)
    }

    suspend fun restoreHighlight(id: String) {
        val now = System.currentTimeMillis()
        highlightDao.restore(id, now)
        flashcards.restoreForSource(id, now)
    }

    // ---- Colour meanings -----------------------------------------------------------------------

    fun observeMeanings(profileId: String): Flow<Map<HighlightColor, String>> =
        meaningDao.observe(profileId).map { rows ->
            val byKey = rows.associate { it.colorKey to it.label }
            HighlightColor.entries.associateWith { byKey[it.name] ?: it.defaultMeaning }
        }

    suspend fun setMeaning(profileId: String, color: HighlightColor, label: String) =
        meaningDao.upsert(ColorMeaningEntity(profileId, color.name, label.trim().ifEmpty { color.defaultMeaning }.take(40)))

    // ---- Notes and history -----------------------------------------------------------------------

    fun observeNotes(profileId: String): Flow<List<NoteEntity>> = noteDao.observeForProfile(profileId)

    fun observeNotesForBook(profileId: String, bookId: String): Flow<List<NoteEntity>> = noteDao.observeForBook(profileId, bookId)

    fun observeNote(id: String): Flow<NoteEntity?> = noteDao.observe(id)

    fun observeVersions(noteId: String): Flow<List<NoteVersionEntity>> = versionDao.observeForNote(noteId)

    suspend fun noteForHighlight(highlightId: String): NoteEntity? = noteDao.findForHighlight(highlightId)

    suspend fun notesForBook(profileId: String, bookId: String): List<NoteEntity> = noteDao.getForBook(profileId, bookId)

    /**
     * Saves a note. When an existing note's text changes, the previous text is stored as a version first,
     * so every edit can be undone from note history.
     */
    suspend fun saveNote(
        profileId: String,
        id: String?,
        title: String,
        body: String,
        bookId: String? = null,
        highlightId: String? = null,
        position: Int? = null,
    ): NoteEntity {
        val now = System.currentTimeMillis()
        val existing = id?.let { noteDao.get(it) }
        if (existing != null) {
            if (existing.title == title && existing.body == body) return existing
            versionDao.insert(NoteVersionEntity(UUID.randomUUID().toString(), existing.id, existing.title, existing.body, existing.modifiedAt, "EDIT"))
            val updated = existing.copy(title = title, body = body, modifiedAt = now, deletedAt = null)
            noteDao.upsert(updated)
            return updated
        }
        val note = NoteEntity(UUID.randomUUID().toString(), profileId, bookId, highlightId, position, title, body, now)
        noteDao.upsert(note)
        return note
    }

    /** Restores an earlier version. The current text is saved as a version too, so restoring loses nothing. */
    suspend fun restoreVersion(version: NoteVersionEntity) {
        val note = noteDao.get(version.noteId) ?: return
        val now = System.currentTimeMillis()
        versionDao.insert(NoteVersionEntity(UUID.randomUUID().toString(), note.id, note.title, note.body, note.modifiedAt, "RESTORE"))
        noteDao.upsert(note.copy(title = version.title, body = version.body, modifiedAt = now, deletedAt = null))
    }

    /** Saves a losing sync version into history without changing the current note. */
    suspend fun keepAsVersion(note: NoteEntity, origin: String) {
        versionDao.insert(NoteVersionEntity(UUID.randomUUID().toString(), note.id, note.title, note.body, note.modifiedAt, origin))
    }

    suspend fun deleteNote(id: String) = noteDao.softDelete(id, System.currentTimeMillis())

    suspend fun restoreNote(id: String) = noteDao.restore(id, System.currentTimeMillis())

    // ---- Ink ---------------------------------------------------------------------------------------

    fun observeInk(profileId: String, bookId: String): Flow<List<InkStrokeEntity>> = inkDao.observeForBook(profileId, bookId)

    suspend fun inkFor(profileId: String, bookId: String): List<InkStrokeEntity> = inkDao.getForBook(profileId, bookId)

    suspend fun insertInk(stroke: InkStrokeEntity) = inkDao.upsert(stroke.copy(deletedAt = null, modifiedAt = System.currentTimeMillis()))

    suspend fun deleteInk(id: String) = inkDao.softDelete(id, System.currentTimeMillis())

    suspend fun restoreInk(id: String) = inkDao.restore(id, System.currentTimeMillis())
}
