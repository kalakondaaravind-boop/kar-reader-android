package com.kar.reader.data.repo

import com.kar.reader.data.db.BookDao
import com.kar.reader.data.db.BookEntity
import com.kar.reader.data.db.InkDao
import com.kar.reader.data.db.ProgressDao
import com.kar.reader.data.files.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A library row with everything the library and home screens display. */
data class LibraryItem(
    val book: BookEntity,
    val totalProgression: Float?,
    val inkCount: Int,
    val coverFile: File?,
    val fileMissing: Boolean,
)

@Singleton
class LibraryRepository @Inject constructor(
    private val bookDao: BookDao,
    private val progressDao: ProgressDao,
    private val inkDao: InkDao,
    private val store: DocumentStore,
) {

    fun observeLibrary(): Flow<List<LibraryItem>> =
        combine(bookDao.observeAll(), progressDao.observeAll(), inkDao.observeCounts()) { books, progress, counts ->
            val progressById = progress.associateBy { it.bookId }
            val countById = counts.associate { it.bookId to it.count }
            books.map { book ->
                val cover = store.coverFile(book.id)
                LibraryItem(
                    book = book,
                    totalProgression = progressById[book.id]?.totalProgression,
                    inkCount = countById[book.id] ?: 0,
                    coverFile = cover.takeIf { it.isFile },
                    fileMissing = !store.libraryFile(book.storedFileName).isFile,
                )
            }
        }

    fun observeBook(id: String): Flow<BookEntity?> = bookDao.observe(id)

    suspend fun getBook(id: String): BookEntity? = bookDao.get(id)

    fun fileFor(book: BookEntity): File = store.libraryFile(book.storedFileName)

    suspend fun markOpened(id: String) = bookDao.markOpened(id, System.currentTimeMillis())

    suspend fun setFavorite(id: String, favorite: Boolean) = bookDao.setFavorite(id, favorite)

    suspend fun setUserMetadata(id: String, title: String?, author: String?) =
        bookDao.setUserMetadata(id, title?.trim()?.ifEmpty { null }, author?.trim()?.ifEmpty { null })

    /**
     * Removes a book, its app-managed copy, and (by cascade) its progress, bookmarks and annotations.
     * The UI must confirm this explicitly; the user's original file outside the app is never touched.
     */
    suspend fun removeBook(id: String) = withContext(Dispatchers.IO) {
        val book = bookDao.get(id) ?: return@withContext
        bookDao.delete(id)
        store.libraryFile(book.storedFileName).delete()
        store.coverFile(id).delete()
    }
}
