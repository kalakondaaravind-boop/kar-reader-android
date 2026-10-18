package com.kar.reader.data.repo

import com.kar.reader.core.domain.BookFacts
import com.kar.reader.core.domain.SmartQuery
import com.kar.reader.core.domain.TrashPolicy
import com.kar.reader.data.db.BookCount
import com.kar.reader.data.db.BookDao
import com.kar.reader.data.db.BookEntity
import com.kar.reader.data.db.BookStateDao
import com.kar.reader.data.db.BookStateEntity
import com.kar.reader.data.db.BookTagEntity
import com.kar.reader.data.db.CollectionBookEntity
import com.kar.reader.data.db.CollectionDao
import com.kar.reader.data.db.CollectionEntity
import com.kar.reader.data.db.HighlightDao
import com.kar.reader.data.db.InkDao
import com.kar.reader.data.db.NoteDao
import com.kar.reader.data.db.ProgressDao
import com.kar.reader.data.db.ReadingProgressEntity
import com.kar.reader.data.db.TagDao
import com.kar.reader.data.db.TagEntity
import com.kar.reader.data.db.displayAuthor
import com.kar.reader.data.files.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A library row with everything the library, home and collection screens show. */
data class LibraryItem(
    val book: BookEntity,
    val state: BookStateEntity?,
    val progress: ReadingProgressEntity?,
    val highlightCount: Int,
    val noteCount: Int,
    val inkCount: Int,
    val tags: List<String>,
    val coverFile: File?,
    val fileMissing: Boolean,
) {
    val favorite: Boolean get() = state?.favorite == true
    val isPrivate: Boolean get() = state?.isPrivate == true
    val progressPercent: Int get() = ((progress?.totalProgression ?: 0f) * 100).toInt().coerceIn(0, 100)
    val lastOpenedAt: Long? get() = state?.lastOpenedAt

    fun facts(): BookFacts = BookFacts(
        format = book.format,
        addedAt = book.addedAt,
        lastOpenedAt = lastOpenedAt,
        progressPercent = progressPercent,
        highlightCount = highlightCount,
        noteCount = noteCount,
        rating = book.rating,
        favorite = favorite,
        tags = tags.toSet(),
        author = book.displayAuthor.orEmpty(),
    )
}

@Singleton
class LibraryRepository @Inject constructor(
    private val bookDao: BookDao,
    private val stateDao: BookStateDao,
    private val progressDao: ProgressDao,
    private val highlightDao: HighlightDao,
    private val noteDao: NoteDao,
    private val inkDao: InkDao,
    private val tagDao: TagDao,
    private val collectionDao: CollectionDao,
    private val store: DocumentStore,
    private val widgets: WidgetRefresher,
) {

    fun observeLibrary(profileId: String): Flow<List<LibraryItem>> {
        val counts: Flow<Triple<Map<String, Int>, Map<String, Int>, Map<String, Int>>> = combine(
            highlightDao.observeCounts(profileId),
            noteDao.observeCounts(profileId),
            inkDao.observeCounts(profileId),
        ) { h, n, i -> Triple(h.toMap(), n.toMap(), i.toMap()) }
        return combine(
            bookDao.observeActive(),
            stateDao.observeForProfile(profileId),
            progressDao.observeForProfile(profileId),
            counts,
            tagDao.observeBookTags(),
        ) { books, states, progress, (h, n, i), tags ->
            val stateById = states.associateBy { it.bookId }
            val progressById = progress.associateBy { it.bookId }
            val tagsByBook = tags.groupBy({ it.bookId }, { it.name })
            books.map { book ->
                val cover = store.coverFile(book.id)
                LibraryItem(
                    book = book,
                    state = stateById[book.id],
                    progress = progressById[book.id],
                    highlightCount = h[book.id] ?: 0,
                    noteCount = n[book.id] ?: 0,
                    inkCount = i[book.id] ?: 0,
                    tags = tagsByBook[book.id].orEmpty().sortedBy { it.lowercase() },
                    coverFile = cover.takeIf { it.isFile },
                    fileMissing = !store.libraryFile(book.storedFileName).isFile,
                )
            }
        }
    }

    private fun List<BookCount>.toMap() = associate { it.bookId to it.count }

    fun observeBook(id: String): Flow<BookEntity?> = bookDao.observe(id)

    suspend fun getBook(id: String): BookEntity? = bookDao.get(id)

    fun fileFor(book: BookEntity): File = store.libraryFile(book.storedFileName)

    suspend fun markOpened(profileId: String, bookId: String) {
        val now = System.currentTimeMillis()
        val state = stateDao.get(profileId, bookId) ?: BookStateEntity(profileId, bookId)
        stateDao.upsert(state.copy(lastOpenedAt = now, modifiedAt = now))
        widgets.requestRefresh()
    }

    suspend fun setFavorite(profileId: String, bookId: String, favorite: Boolean) = updateState(profileId, bookId) { it.copy(favorite = favorite) }

    suspend fun setPrivate(profileId: String, bookId: String, private: Boolean) {
        updateState(profileId, bookId) { it.copy(isPrivate = private) }
        widgets.requestRefresh()
    }

    suspend fun setFinished(profileId: String, bookId: String, finishedAt: Long?) = updateState(profileId, bookId) { it.copy(finishedAt = finishedAt) }

    private suspend fun updateState(profileId: String, bookId: String, change: (BookStateEntity) -> BookStateEntity) {
        val state = stateDao.get(profileId, bookId) ?: BookStateEntity(profileId, bookId)
        stateDao.upsert(change(state).copy(modifiedAt = System.currentTimeMillis()))
    }

    suspend fun updateDetails(
        bookId: String,
        title: String?,
        author: String?,
        year: String?,
        publisher: String?,
        rating: Int,
        tags: List<String>,
    ) {
        val book = bookDao.get(bookId) ?: return
        bookDao.update(
            book.copy(
                userTitle = title?.trim()?.ifEmpty { null }?.takeIf { it != book.title },
                userAuthor = author?.trim()?.ifEmpty { null }?.takeIf { it != book.author },
                year = year?.trim()?.ifEmpty { null },
                publisher = publisher?.trim()?.ifEmpty { null },
                rating = rating.coerceIn(0, 5),
                modifiedAt = System.currentTimeMillis(),
            ),
        )
        setTags(bookId, tags)
    }

    suspend fun setTags(bookId: String, tags: List<String>) {
        tagDao.clearBookTags(bookId)
        tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.forEach { name ->
            val tag = tagDao.findByName(name) ?: TagEntity(UUID.randomUUID().toString(), name.take(40)).also { tagDao.insert(it) }
            val id = tagDao.findByName(name)?.id ?: tag.id
            tagDao.addBookTag(BookTagEntity(bookId, id))
        }
    }

    suspend fun tagsFor(bookId: String): List<String> = tagDao.namesForBook(bookId)

    // ---- Recycle bin -----------------------------------------------------------------------------

    /** Moves a book (and, implicitly, its notes and highlights) to the recycle bin. Nothing is deleted yet. */
    suspend fun moveToBin(bookId: String) {
        bookDao.softDelete(bookId, System.currentTimeMillis())
        widgets.requestRefresh()
    }

    suspend fun restoreBook(bookId: String) {
        bookDao.restore(bookId, System.currentTimeMillis())
        widgets.requestRefresh()
    }

    fun observeDeletedBooks(): Flow<List<BookEntity>> = bookDao.observeDeleted()

    /** Permanently removes books whose 30 days in the bin are over, including their app-managed files. */
    suspend fun purgeExpiredBooks(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val expired = bookDao.getDeletedBefore(TrashPolicy.purgeBefore(now))
        expired.forEach { deleteForever(it) }
        expired.size
    }

    suspend fun deleteForever(book: BookEntity) = withContext(Dispatchers.IO) {
        bookDao.deleteHard(book.id)
        store.libraryFile(book.storedFileName).delete()
        store.coverFile(book.id).delete()
    }

    // ---- Collections ---------------------------------------------------------------------------

    fun observeCollections(profileId: String): Flow<List<CollectionEntity>> = collectionDao.observeForProfile(profileId)

    fun observeCollectionMembers(): Flow<List<CollectionBookEntity>> = collectionDao.observeMembers()

    suspend fun saveCollection(profileId: String, id: String?, name: String, smart: SmartQuery?): CollectionEntity {
        val now = System.currentTimeMillis()
        val existing = id?.let { collectionDao.get(it) }
        val collection = (existing ?: CollectionEntity(UUID.randomUUID().toString(), profileId, name, null, now))
            .copy(name = name.trim().ifEmpty { "Untitled" }.take(60), smartQuery = smart?.encode(), modifiedAt = now)
        collectionDao.upsert(collection)
        return collection
    }

    suspend fun getCollection(id: String): CollectionEntity? = collectionDao.get(id)

    suspend fun deleteCollection(id: String) = collectionDao.delete(id)

    suspend fun addToCollection(collectionId: String, bookId: String) = collectionDao.addMember(CollectionBookEntity(collectionId, bookId))

    suspend fun removeFromCollection(collectionId: String, bookId: String) = collectionDao.removeMember(collectionId, bookId)
}
