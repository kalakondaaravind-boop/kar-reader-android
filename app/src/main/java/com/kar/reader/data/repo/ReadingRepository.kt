package com.kar.reader.data.repo

import com.kar.reader.data.db.BookmarkDao
import com.kar.reader.data.db.BookmarkEntity
import com.kar.reader.data.db.InkAnnotationEntity
import com.kar.reader.data.db.InkDao
import com.kar.reader.data.db.ProgressDao
import com.kar.reader.data.db.ReaderPreferencesDao
import com.kar.reader.data.db.ReaderPreferencesEntity
import com.kar.reader.data.db.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadingRepository @Inject constructor(
    private val progressDao: ProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val inkDao: InkDao,
    private val prefsDao: ReaderPreferencesDao,
) {
    suspend fun getProgress(bookId: String): ReadingProgressEntity? = progressDao.get(bookId)

    suspend fun saveProgress(bookId: String, position: Int, progression: Float, totalProgression: Float) {
        progressDao.upsert(
            ReadingProgressEntity(
                bookId = bookId,
                position = position,
                progression = progression.coerceIn(0f, 1f),
                totalProgression = totalProgression.coerceIn(0f, 1f),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>> = bookmarkDao.observeForBook(bookId)

    fun observeAllBookmarks(): Flow<List<BookmarkEntity>> = bookmarkDao.observeAll()

    suspend fun addBookmark(bookId: String, position: Int, progression: Float, label: String): BookmarkEntity {
        val bookmark = BookmarkEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            position = position,
            progression = progression.coerceIn(0f, 1f),
            label = label,
            createdAt = System.currentTimeMillis(),
        )
        bookmarkDao.insert(bookmark)
        return bookmark
    }

    suspend fun deleteBookmark(id: String) = bookmarkDao.delete(id)

    fun observeInk(bookId: String): Flow<List<InkAnnotationEntity>> = inkDao.observeForBook(bookId)

    suspend fun getInk(bookId: String): List<InkAnnotationEntity> = inkDao.getForBook(bookId)

    suspend fun insertInk(stroke: InkAnnotationEntity) = inkDao.insert(stroke)

    suspend fun deleteInk(id: String) = inkDao.delete(id)

    fun observeReaderPreferences(bookId: String): Flow<ReaderPreferencesEntity> =
        prefsDao.observe(bookId).map { it ?: ReaderPreferencesEntity(bookId) }

    suspend fun saveReaderPreferences(prefs: ReaderPreferencesEntity) = prefsDao.upsert(prefs)
}
