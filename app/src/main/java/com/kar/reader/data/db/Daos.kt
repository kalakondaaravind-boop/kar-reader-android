package com.kar.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class BookCount(val bookId: String, val count: Int)

data class BookTagName(val bookId: String, val name: String)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY created_at")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY created_at")
    suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE deleted_at IS NULL ORDER BY added_at DESC")
    fun observeActive(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeDeleted(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE content_hash = :hash")
    suspend fun findByHash(hash: String): BookEntity?

    @Query("SELECT * FROM books")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT * FROM books WHERE deleted_at IS NULL")
    suspend fun getActive(): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET deleted_at = :time, modified_at = :time WHERE id = :id")
    suspend fun softDelete(id: String, time: Long)

    @Query("UPDATE books SET deleted_at = NULL, modified_at = :time WHERE id = :id")
    suspend fun restore(id: String, time: Long)

    @Query("SELECT * FROM books WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun getDeletedBefore(cutoff: Long): List<BookEntity>

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteHard(id: String)
}

@Dao
interface BookStateDao {
    @Query("SELECT * FROM book_states WHERE profile_id = :profileId")
    fun observeForProfile(profileId: String): Flow<List<BookStateEntity>>

    @Query("SELECT * FROM book_states WHERE profile_id = :profileId AND book_id = :bookId")
    suspend fun get(profileId: String, bookId: String): BookStateEntity?

    @Query("SELECT * FROM book_states")
    suspend fun getAll(): List<BookStateEntity>

    @Query("SELECT * FROM book_states WHERE profile_id = :profileId AND last_opened_at IS NOT NULL ORDER BY last_opened_at DESC LIMIT 20")
    suspend fun recent(profileId: String): List<BookStateEntity>

    @Upsert
    suspend fun upsert(state: BookStateEntity)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM reading_progress WHERE profile_id = :profileId AND book_id = :bookId")
    suspend fun get(profileId: String, bookId: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE profile_id = :profileId")
    fun observeForProfile(profileId: String): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress WHERE profile_id = :profileId")
    suspend fun getForProfile(profileId: String): List<ReadingProgressEntity>

    @Query("SELECT * FROM reading_progress")
    suspend fun getAll(): List<ReadingProgressEntity>

    @Upsert
    suspend fun upsert(progress: ReadingProgressEntity)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE profile_id = :profileId AND book_id = :bookId AND deleted_at IS NULL ORDER BY position, progression")
    fun observeForBook(profileId: String, bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE profile_id = :profileId AND deleted_at IS NULL ORDER BY created_at DESC")
    fun observeForProfile(profileId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE profile_id = :profileId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeDeleted(profileId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE profile_id = :profileId")
    suspend fun getForProfile(profileId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks")
    suspend fun getAll(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("UPDATE bookmarks SET deleted_at = :time, modified_at = :time WHERE id = :id")
    suspend fun softDelete(id: String, time: Long)

    @Query("UPDATE bookmarks SET deleted_at = NULL, modified_at = :time WHERE id = :id")
    suspend fun restore(id: String, time: Long)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteHard(id: String)

    @Query("DELETE FROM bookmarks WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun purge(cutoff: Long): Int
}

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE profile_id = :profileId AND book_id = :bookId AND deleted_at IS NULL ORDER BY position, created_at")
    fun observeForBook(profileId: String, bookId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE profile_id = :profileId AND deleted_at IS NULL ORDER BY created_at DESC")
    fun observeForProfile(profileId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE profile_id = :profileId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeDeleted(profileId: String): Flow<List<HighlightEntity>>

    @Query("SELECT book_id AS bookId, COUNT(*) AS count FROM highlights WHERE profile_id = :profileId AND deleted_at IS NULL GROUP BY book_id")
    fun observeCounts(profileId: String): Flow<List<BookCount>>

    @Query("SELECT * FROM highlights WHERE id = :id")
    suspend fun get(id: String): HighlightEntity?

    @Query("SELECT * FROM highlights WHERE profile_id = :profileId AND book_id = :bookId AND deleted_at IS NULL ORDER BY position, created_at")
    suspend fun getForBook(profileId: String, bookId: String): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE profile_id = :profileId")
    suspend fun getForProfile(profileId: String): List<HighlightEntity>

    @Query("SELECT * FROM highlights")
    suspend fun getAll(): List<HighlightEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(highlight: HighlightEntity): Long

    @Upsert
    suspend fun upsert(highlight: HighlightEntity)

    @Query("UPDATE highlights SET color_key = :colorKey, modified_at = :time WHERE id = :id")
    suspend fun setColor(id: String, colorKey: String, time: Long)

    @Query("UPDATE highlights SET deleted_at = :time, modified_at = :time WHERE id = :id")
    suspend fun softDelete(id: String, time: Long)

    @Query("UPDATE highlights SET deleted_at = NULL, modified_at = :time WHERE id = :id")
    suspend fun restore(id: String, time: Long)

    @Query("DELETE FROM highlights WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun purge(cutoff: Long): Int
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE profile_id = :profileId AND deleted_at IS NULL ORDER BY modified_at DESC")
    fun observeForProfile(profileId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE profile_id = :profileId AND book_id = :bookId AND deleted_at IS NULL ORDER BY modified_at DESC")
    fun observeForBook(profileId: String, bookId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE profile_id = :profileId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeDeleted(profileId: String): Flow<List<NoteEntity>>

    @Query("SELECT book_id AS bookId, COUNT(*) AS count FROM notes WHERE profile_id = :profileId AND deleted_at IS NULL AND book_id IS NOT NULL GROUP BY book_id")
    fun observeCounts(profileId: String): Flow<List<BookCount>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE highlight_id = :highlightId AND deleted_at IS NULL LIMIT 1")
    suspend fun findForHighlight(highlightId: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE profile_id = :profileId AND book_id = :bookId AND deleted_at IS NULL")
    suspend fun getForBook(profileId: String, bookId: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE profile_id = :profileId")
    suspend fun getForProfile(profileId: String): List<NoteEntity>

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("UPDATE notes SET deleted_at = :time, modified_at = :time WHERE id = :id")
    suspend fun softDelete(id: String, time: Long)

    @Query("UPDATE notes SET deleted_at = NULL, modified_at = :time WHERE id = :id")
    suspend fun restore(id: String, time: Long)

    @Query("DELETE FROM notes WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun purge(cutoff: Long): Int
}

@Dao
interface NoteVersionDao {
    @Query("SELECT * FROM note_versions WHERE note_id = :noteId ORDER BY saved_at DESC")
    fun observeForNote(noteId: String): Flow<List<NoteVersionEntity>>

    @Query("SELECT * FROM note_versions")
    suspend fun getAll(): List<NoteVersionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(version: NoteVersionEntity): Long
}

@Dao
interface InkDao {
    @Query("SELECT * FROM ink_strokes WHERE profile_id = :profileId AND book_id = :bookId AND deleted_at IS NULL ORDER BY created_at")
    fun observeForBook(profileId: String, bookId: String): Flow<List<InkStrokeEntity>>

    @Query("SELECT * FROM ink_strokes WHERE profile_id = :profileId AND book_id = :bookId AND deleted_at IS NULL ORDER BY created_at")
    suspend fun getForBook(profileId: String, bookId: String): List<InkStrokeEntity>

    @Query("SELECT * FROM ink_strokes WHERE profile_id = :profileId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeDeleted(profileId: String): Flow<List<InkStrokeEntity>>

    @Query("SELECT book_id AS bookId, COUNT(*) AS count FROM ink_strokes WHERE profile_id = :profileId AND deleted_at IS NULL GROUP BY book_id")
    fun observeCounts(profileId: String): Flow<List<BookCount>>

    @Query("SELECT * FROM ink_strokes WHERE profile_id = :profileId")
    suspend fun getForProfile(profileId: String): List<InkStrokeEntity>

    @Query("SELECT * FROM ink_strokes")
    suspend fun getAll(): List<InkStrokeEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(stroke: InkStrokeEntity): Long

    @Upsert
    suspend fun upsert(stroke: InkStrokeEntity)

    @Query("UPDATE ink_strokes SET deleted_at = :time, modified_at = :time WHERE id = :id")
    suspend fun softDelete(id: String, time: Long)

    @Query("UPDATE ink_strokes SET deleted_at = NULL, modified_at = :time WHERE id = :id")
    suspend fun restore(id: String, time: Long)

    @Query("DELETE FROM ink_strokes WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun purge(cutoff: Long): Int
}

@Dao
interface ReaderPreferencesDao {
    @Query("SELECT * FROM reader_preferences WHERE profile_id = :profileId AND book_id = :bookId")
    fun observe(profileId: String, bookId: String): Flow<ReaderPreferencesEntity?>

    @Query("SELECT * FROM reader_preferences")
    suspend fun getAll(): List<ReaderPreferencesEntity>

    @Upsert
    suspend fun upsert(prefs: ReaderPreferencesEntity)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Query("SELECT bt.book_id AS bookId, t.name AS name FROM book_tags bt JOIN tags t ON t.id = bt.tag_id")
    fun observeBookTags(): Flow<List<BookTagName>>

    @Query("SELECT t.name FROM book_tags bt JOIN tags t ON t.id = bt.tag_id WHERE bt.book_id = :bookId ORDER BY t.name COLLATE NOCASE")
    suspend fun namesForBook(bookId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBookTag(link: BookTagEntity): Long

    @Query("DELETE FROM book_tags WHERE book_id = :bookId")
    suspend fun clearBookTags(bookId: String)

    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM book_tags")
    suspend fun getAllLinks(): List<BookTagEntity>
}

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections WHERE profile_id = :profileId ORDER BY name COLLATE NOCASE")
    fun observeForProfile(profileId: String): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun get(id: String): CollectionEntity?

    @Query("SELECT * FROM collection_books")
    fun observeMembers(): Flow<List<CollectionBookEntity>>

    @Upsert
    suspend fun upsert(collection: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMember(member: CollectionBookEntity): Long

    @Query("DELETE FROM collection_books WHERE collection_id = :collectionId AND book_id = :bookId")
    suspend fun removeMember(collectionId: String, bookId: String)

    @Query("SELECT * FROM collections")
    suspend fun getAll(): List<CollectionEntity>

    @Query("SELECT * FROM collection_books")
    suspend fun getAllMembers(): List<CollectionBookEntity>
}

@Dao
interface ColorMeaningDao {
    @Query("SELECT * FROM color_meanings WHERE profile_id = :profileId")
    fun observe(profileId: String): Flow<List<ColorMeaningEntity>>

    @Query("SELECT * FROM color_meanings WHERE profile_id = :profileId")
    suspend fun getForProfile(profileId: String): List<ColorMeaningEntity>

    @Query("SELECT * FROM color_meanings")
    suspend fun getAll(): List<ColorMeaningEntity>

    @Upsert
    suspend fun upsert(meaning: ColorMeaningEntity)
}

@Dao
interface FlashcardDao {
    @Query("SELECT * FROM flashcards WHERE profile_id = :profileId AND deleted_at IS NULL ORDER BY due_at")
    fun observeForProfile(profileId: String): Flow<List<FlashcardEntity>>

    @Query("SELECT COUNT(*) FROM flashcards WHERE profile_id = :profileId AND deleted_at IS NULL AND due_at <= :now")
    suspend fun countDue(profileId: String, now: Long): Int

    @Query("SELECT * FROM flashcards WHERE id = :id")
    suspend fun get(id: String): FlashcardEntity?

    @Query("SELECT COUNT(*) FROM flashcards WHERE source_id = :sourceId AND deleted_at IS NULL")
    suspend fun countForSource(sourceId: String): Int

    @Query("SELECT * FROM flashcards WHERE profile_id = :profileId")
    suspend fun getForProfile(profileId: String): List<FlashcardEntity>

    @Query("SELECT * FROM flashcards")
    suspend fun getAll(): List<FlashcardEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(card: FlashcardEntity): Long

    @Upsert
    suspend fun upsert(card: FlashcardEntity)

    @Query("UPDATE flashcards SET deleted_at = :time, modified_at = :time WHERE source_id = :sourceId")
    suspend fun softDeleteForSource(sourceId: String, time: Long)

    @Query("UPDATE flashcards SET deleted_at = NULL, modified_at = :time WHERE source_id = :sourceId")
    suspend fun restoreForSource(sourceId: String, time: Long)

    @Query("DELETE FROM flashcards WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun purge(cutoff: Long): Int
}

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary WHERE profile_id = :profileId AND deleted_at IS NULL ORDER BY word COLLATE NOCASE")
    fun observeForProfile(profileId: String): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary WHERE profile_id = :profileId")
    suspend fun getForProfile(profileId: String): List<VocabularyEntity>

    @Query("SELECT * FROM vocabulary")
    suspend fun getAll(): List<VocabularyEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(word: VocabularyEntity): Long

    @Upsert
    suspend fun upsert(word: VocabularyEntity)

    @Query("UPDATE vocabulary SET deleted_at = :time, modified_at = :time WHERE id = :id")
    suspend fun softDelete(id: String, time: Long)

    @Query("UPDATE vocabulary SET deleted_at = NULL, modified_at = :time WHERE id = :id")
    suspend fun restore(id: String, time: Long)

    @Query("SELECT * FROM vocabulary WHERE profile_id = :profileId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeDeleted(profileId: String): Flow<List<VocabularyEntity>>

    @Query("DELETE FROM vocabulary WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun purge(cutoff: Long): Int
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM reading_sessions WHERE profile_id = :profileId ORDER BY started_at DESC")
    fun observeForProfile(profileId: String): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE profile_id = :profileId")
    suspend fun getForProfile(profileId: String): List<ReadingSessionEntity>

    @Query("SELECT * FROM reading_sessions")
    suspend fun getAll(): List<ReadingSessionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(session: ReadingSessionEntity): Long

    @Query("DELETE FROM reading_sessions WHERE profile_id = :profileId")
    suspend fun clearForProfile(profileId: String)
}

@Dao
interface PageTextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pages: List<PageTextEntity>)

    @Query("SELECT * FROM page_text WHERE book_id = :bookId ORDER BY page")
    suspend fun forBook(bookId: String): List<PageTextEntity>

    @Query("SELECT * FROM page_text")
    suspend fun getAll(): List<PageTextEntity>
}

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebooks WHERE profile_id = :profileId AND deleted_at IS NULL ORDER BY modified_at DESC")
    fun observeForProfile(profileId: String): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE profile_id = :profileId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeDeleted(profileId: String): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    fun observe(id: String): Flow<NotebookEntity?>

    @Query("SELECT * FROM notebooks WHERE profile_id = :profileId")
    suspend fun getForProfile(profileId: String): List<NotebookEntity>

    @Query("SELECT * FROM notebooks")
    suspend fun getAll(): List<NotebookEntity>

    @Upsert
    suspend fun upsert(notebook: NotebookEntity)

    @Query("UPDATE notebooks SET deleted_at = :time, modified_at = :time WHERE id = :id")
    suspend fun softDelete(id: String, time: Long)

    @Query("UPDATE notebooks SET deleted_at = NULL, modified_at = :time WHERE id = :id")
    suspend fun restore(id: String, time: Long)

    @Query("DELETE FROM notebooks WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun purge(cutoff: Long): Int

    @Query("SELECT * FROM notebook_pages WHERE notebook_id = :notebookId ORDER BY page_index")
    fun observePages(notebookId: String): Flow<List<NotebookPageEntity>>

    @Query("SELECT * FROM notebook_pages WHERE notebook_id = :notebookId ORDER BY page_index")
    suspend fun getPages(notebookId: String): List<NotebookPageEntity>

    @Query("SELECT * FROM notebook_pages")
    suspend fun getAllPages(): List<NotebookPageEntity>

    @Upsert
    suspend fun upsertPage(page: NotebookPageEntity)

    @Query("SELECT * FROM notebook_strokes WHERE page_id = :pageId AND deleted_at IS NULL ORDER BY created_at")
    fun observeStrokes(pageId: String): Flow<List<NotebookStrokeEntity>>

    @Query("SELECT * FROM notebook_strokes")
    suspend fun getAllStrokes(): List<NotebookStrokeEntity>

    @Upsert
    suspend fun upsertStroke(stroke: NotebookStrokeEntity)

    @Query("UPDATE notebook_strokes SET deleted_at = :time, modified_at = :time WHERE id IN (:ids)")
    suspend fun softDeleteStrokes(ids: List<String>, time: Long)

    @Query("UPDATE notebook_strokes SET deleted_at = NULL, modified_at = :time WHERE id IN (:ids)")
    suspend fun restoreStrokes(ids: List<String>, time: Long)

    @Query("DELETE FROM notebook_strokes WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun purgeStrokes(cutoff: Long): Int
}
