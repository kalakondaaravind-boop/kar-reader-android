@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kar.reader.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.domain.SmartQuery
import com.kar.reader.core.lock.AppLockManager
import com.kar.reader.data.db.CollectionEntity
import com.kar.reader.data.db.displayAuthor
import com.kar.reader.data.db.displayTitle
import com.kar.reader.data.files.DocumentImporter
import com.kar.reader.data.files.ImportResult
import com.kar.reader.data.files.StagedBatch
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.prefs.LibrarySort
import com.kar.reader.data.repo.LibraryItem
import com.kar.reader.data.repo.LibraryRepository
import com.kar.reader.data.repo.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryFilter(val label: String) { ALL("All"), EPUB("EPUB"), PDF("PDF"), FAVORITES("Favorites"), PRIVATE("Private") }

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Working(val message: String) : ImportUiState
    data class Preview(val batch: StagedBatch) : ImportUiState
    data class Done(val imported: Int, val duplicates: Int, val failed: List<String>, val skipped: List<String>) : ImportUiState
}

data class CollectionChip(val collection: CollectionEntity, val count: Int, val smart: Boolean)

data class LibraryUiState(
    val items: List<LibraryItem> = emptyList(),
    val totalCount: Int = 0,
    val collections: List<CollectionChip> = emptyList(),
    val selectedCollection: String? = null,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.RECENT,
    val grid: Boolean = true,
    val privateUnlocked: Boolean = false,
    val loaded: Boolean = false,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val library: LibraryRepository,
    private val importer: DocumentImporter,
    private val prefs: AppPreferences,
    val lock: AppLockManager,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val selectedCollection = MutableStateFlow<String?>(null)

    private val _import = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _import.asStateFlow()
    private var importJob: Job? = null

    private val data = profiles.currentId.flatMapLatest { pid ->
        combine(library.observeLibrary(pid), library.observeCollections(pid), library.observeCollectionMembers()) { items, cols, members ->
            Triple(items, cols, members)
        }
    }

    val state: StateFlow<LibraryUiState> = combine(
        data,
        combine(query, filter, selectedCollection) { q, f, c -> Triple(q, f, c) },
        prefs.settings,
        lock.privateUnlocked,
    ) { (items, cols, members), (q, f, c), settings, unlocked ->
        val now = System.currentTimeMillis()
        val visible = items.filter { unlocked || !it.isPrivate }
        val membersByCollection = members.groupBy({ it.collectionId }, { it.bookId })
        val chips = cols.map { col ->
            val smart = SmartQuery.decode(col.smartQuery)
            val count = if (smart != null) visible.count { smart.matches(it.facts(), now) } else membersByCollection[col.id].orEmpty().count { id -> visible.any { it.book.id == id } }
            CollectionChip(col, count, smart != null)
        }
        var list = visible
        val selected = cols.firstOrNull { it.id == c }
        if (selected != null) {
            val smart = SmartQuery.decode(selected.smartQuery)
            list = if (smart != null) list.filter { smart.matches(it.facts(), now) } else membersByCollection[selected.id].orEmpty().toSet().let { ids -> list.filter { it.book.id in ids } }
        }
        list = when (f) {
            LibraryFilter.ALL -> list
            LibraryFilter.EPUB -> list.filter { it.book.format == "EPUB" }
            LibraryFilter.PDF -> list.filter { it.book.format == "PDF" }
            LibraryFilter.FAVORITES -> list.filter { it.favorite }
            LibraryFilter.PRIVATE -> list.filter { it.isPrivate }
        }
        if (q.isNotBlank()) {
            val needle = q.trim()
            list = list.filter { item ->
                item.book.displayTitle.contains(needle, true) || item.book.displayAuthor.orEmpty().contains(needle, true) ||
                    item.tags.any { it.contains(needle, true) } || item.book.seriesName.orEmpty().contains(needle, true)
            }
        }
        list = when (settings.librarySort) {
            LibrarySort.RECENT -> list.sortedByDescending { it.lastOpenedAt ?: it.book.addedAt }
            LibrarySort.TITLE -> list.sortedBy { it.book.displayTitle.lowercase() }
            LibrarySort.ADDED -> list.sortedByDescending { it.book.addedAt }
            LibrarySort.AUTHOR -> list.sortedBy { it.book.displayAuthor.orEmpty().lowercase() }
        }
        LibraryUiState(
            items = list,
            totalCount = visible.size,
            collections = chips,
            selectedCollection = selected?.id,
            filter = f,
            query = q,
            sort = settings.librarySort,
            grid = settings.libraryGrid,
            privateUnlocked = unlocked,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(q: String) { query.value = q }
    fun setFilter(f: LibraryFilter) { filter.value = f }
    fun selectCollection(id: String?) { selectedCollection.value = if (selectedCollection.value == id) null else id }
    fun setSort(sort: LibrarySort) = viewModelScope.launch { prefs.setLibrarySort(sort) }
    fun setGrid(grid: Boolean) = viewModelScope.launch { prefs.setLibraryGrid(grid) }

    private suspend fun pid() = profiles.currentIdNow()

    fun setFavorite(item: LibraryItem, on: Boolean) = viewModelScope.launch { library.setFavorite(pid(), item.book.id, on) }
    fun setPrivate(item: LibraryItem, on: Boolean) = viewModelScope.launch { library.setPrivate(pid(), item.book.id, on) }
    fun moveToBin(item: LibraryItem) = viewModelScope.launch { library.moveToBin(item.book.id) }

    fun saveDetails(item: LibraryItem, title: String, author: String, year: String, publisher: String, rating: Int, tags: String) = viewModelScope.launch {
        library.updateDetails(item.book.id, title, author, year, publisher, rating, tags.split(',').map { it.trim() })
    }

    fun toggleCollection(collectionId: String, item: LibraryItem, member: Boolean) = viewModelScope.launch {
        if (member) library.removeFromCollection(collectionId, item.book.id) else library.addToCollection(collectionId, item.book.id)
    }

    fun createCollection(name: String) = viewModelScope.launch { library.saveCollection(pid(), null, name, null) }

    suspend fun collectionMembership(bookId: String): Set<String> =
        library.observeCollectionMembers().first().filter { it.bookId == bookId }.map { it.collectionId }.toSet()

    // ---- Import ------------------------------------------------------------------------------

    fun stage(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _import.value = ImportUiState.Working("Checking files…")
            val batch = runCatching { importer.stage(uris) { _import.value = ImportUiState.Working(it) } }.getOrElse {
                _import.value = ImportUiState.Done(0, 0, listOf(it.message ?: "Import failed"), emptyList()); return@launch
            }
            // ZIP archives (and batches with duplicates or problems) are previewed for explicit confirmation.
            if (batch.containsArchive || batch.problems.isNotEmpty() || batch.documents.any { it.duplicateOf != null }) {
                _import.value = ImportUiState.Preview(batch)
            } else {
                commit(batch)
            }
        }
    }

    fun confirmImport(batch: StagedBatch) {
        importJob = viewModelScope.launch { commit(batch) }
    }

    private suspend fun commit(batch: StagedBatch) {
        val results = importer.commit(batch.newDocuments) { _import.value = ImportUiState.Working(it) }
        importer.discard(batch)
        _import.value = ImportUiState.Done(
            imported = results.count { it is ImportResult.Imported },
            duplicates = results.count { it is ImportResult.Duplicate } + batch.documents.count { it.duplicateOf != null },
            failed = results.filterIsInstance<ImportResult.Failed>().map { "${it.name}: ${it.reason}" } +
                batch.problems.filterIsInstance<com.kar.reader.data.files.ImportProblem.Rejected>().map { "${it.name}: ${it.reason}" },
            skipped = batch.problems.filterIsInstance<com.kar.reader.data.files.ImportProblem.Skipped>().map { "${it.name}: ${it.reason}" },
        )
    }

    fun cancelImport() {
        importJob?.cancel()
        (importState.value as? ImportUiState.Preview)?.let { importer.discard(it.batch) }
        _import.value = ImportUiState.Idle
    }

    fun dismissImportResult() { _import.value = ImportUiState.Idle }
}
