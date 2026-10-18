package com.kar.reader.data.repo

import com.kar.reader.data.db.BookmarkDao
import com.kar.reader.data.db.BookmarkEntity
import com.kar.reader.data.db.PageTextDao
import com.kar.reader.data.db.PageTextEntity
import com.kar.reader.data.db.ProgressDao
import com.kar.reader.data.db.ReaderPreferencesDao
import com.kar.reader.data.db.ReaderPreferencesEntity
import com.kar.reader.data.db.ReadingProgressEntity
import com.kar.reader.data.db.ReadingSessionEntity
import com.kar.reader.data.db.SessionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadingRepository @Inject constructor(
    private val progressDao: ProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val prefsDao: ReaderPreferencesDao,
    private val sessionDao: SessionDao,
    private val pageTextDao: PageTextDao,
    private val library: LibraryRepository,
    private val widgets: WidgetRefresher,
) {
    suspend fun getProgress(profileId: String, bookId: String): ReadingProgressEntity? = progressDao.get(profileId, bookId)

    suspend fun saveProgress(profileId: String, bookId: String, position: Int, progression: Float, totalProgression: Float) {
        val total = totalProgression.coerceIn(0f, 1f)
        progressDao.upsert(
            ReadingProgressEntity(profileId, bookId, position, progression.coerceIn(0f, 1f), total, System.currentTimeMillis()),
        )
        // Mark finished once the reader reaches the very end; reopening and going back does not unmark it.
        if (total >= 0.995f) library.setFinished(profileId, bookId, System.currentTimeMillis())
        widgets.requestRefresh()
    }

    fun observeBookmarks(profileId: String, bookId: String): Flow<List<BookmarkEntity>> = bookmarkDao.observeForBook(profileId, bookId)

    fun observeAllBookmarks(profileId: String): Flow<List<BookmarkEntity>> = bookmarkDao.observeForProfile(profileId)

    suspend fun addBookmark(profileId: String, bookId: String, position: Int, progression: Float, label: String): BookmarkEntity {
        val now = System.currentTimeMillis()
        val bookmark = BookmarkEntity(UUID.randomUUID().toString(), profileId, bookId, position, progression.coerceIn(0f, 1f), label.take(200), now)
        bookmarkDao.insert(bookmark)
        return bookmark
    }

    suspend fun deleteBookmark(id: String) = bookmarkDao.softDelete(id, System.currentTimeMillis())

    fun observeReaderPreferences(profileId: String, bookId: String): Flow<ReaderPreferencesEntity> =
        prefsDao.observe(profileId, bookId).map { it ?: ReaderPreferencesEntity(profileId, bookId) }

    suspend fun saveReaderPreferences(prefs: ReaderPreferencesEntity) = prefsDao.upsert(prefs)

    /** Records a session the reader actually measured. Sessions shorter than 15 seconds are ignored as accidental opens. */
    suspend fun recordSession(profileId: String, bookId: String?, startedAt: Long, durationMs: Long, focus: Boolean) {
        if (durationMs < 15_000) return
        sessionDao.insert(ReadingSessionEntity(UUID.randomUUID().toString(), profileId, bookId, startedAt, durationMs, focus))
        widgets.requestRefresh()
    }

    fun observeSessions(profileId: String): Flow<List<ReadingSessionEntity>> = sessionDao.observeForProfile(profileId)

    suspend fun clearStatistics(profileId: String) {
        sessionDao.clearForProfile(profileId)
        widgets.requestRefresh()
    }

    suspend fun savePageText(pages: List<PageTextEntity>) = pageTextDao.insertAll(pages)

    suspend fun pageText(bookId: String): List<PageTextEntity> = pageTextDao.forBook(bookId)
}
