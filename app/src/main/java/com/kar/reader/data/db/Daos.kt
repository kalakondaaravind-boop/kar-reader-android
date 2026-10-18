package com.kar.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY COALESCE(last_opened_at, added_at) DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE last_opened_at IS NOT NULL ORDER BY last_opened_at DESC LIMIT :limit")
    fun observeRecentlyOpened(limit: Int): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE content_hash = :hash")
    suspend fun findByHash(hash: String): BookEntity?

    @Query("SELECT * FROM books")
    suspend fun getAll(): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(book: BookEntity)

    @Query("UPDATE books SET last_opened_at = :time WHERE id = :id")
    suspend fun markOpened(id: String, time: Long)

    @Query("UPDATE books SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE books SET user_title = :title, user_author = :author WHERE id = :id")
    suspend fun setUserMetadata(id: String, title: String?, author: String?)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM reading_progress WHERE book_id = :bookId")
    suspend fun get(bookId: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress")
    suspend fun getAll(): List<ReadingProgressEntity>

    @Upsert
    suspend fun upsert(progress: ReadingProgressEntity)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY position, progression")
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks")
    suspend fun getAll(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface InkDao {
    @Query("SELECT * FROM ink_annotations WHERE book_id = :bookId ORDER BY created_at")
    fun observeForBook(bookId: String): Flow<List<InkAnnotationEntity>>

    @Query("SELECT * FROM ink_annotations WHERE book_id = :bookId ORDER BY created_at")
    suspend fun getForBook(bookId: String): List<InkAnnotationEntity>

    @Query("SELECT * FROM ink_annotations")
    suspend fun getAll(): List<InkAnnotationEntity>

    @Query("SELECT book_id AS bookId, COUNT(*) AS count FROM ink_annotations GROUP BY book_id")
    fun observeCounts(): Flow<List<BookCount>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(stroke: InkAnnotationEntity): Long

    @Query("DELETE FROM ink_annotations WHERE id = :id")
    suspend fun delete(id: String)
}

data class BookCount(val bookId: String, val count: Int)

@Dao
interface ReaderPreferencesDao {
    @Query("SELECT * FROM reader_preferences WHERE book_id = :bookId")
    fun observe(bookId: String): Flow<ReaderPreferencesEntity?>

    @Query("SELECT * FROM reader_preferences")
    suspend fun getAll(): List<ReaderPreferencesEntity>

    @Upsert
    suspend fun upsert(prefs: ReaderPreferencesEntity)
}
