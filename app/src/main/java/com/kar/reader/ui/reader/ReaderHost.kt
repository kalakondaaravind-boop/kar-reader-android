package com.kar.reader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.data.repo.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ReaderHostViewModel @Inject constructor(savedState: SavedStateHandle, library: LibraryRepository) : ViewModel() {
    private val bookId: String = checkNotNull(savedState.get<String>("bookId"))
    /** "PDF", "EPUB", "MISSING", or null while loading. */
    val format: StateFlow<String?> = library.observeBook(bookId).map { it?.format ?: "MISSING" }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/** Opens the right reader for the book's format. */
@Composable
fun ReaderHost(onBack: () -> Unit, onQuoteCard: (String) -> Unit, vm: ReaderHostViewModel = hiltViewModel()) {
    val format by vm.format.collectAsStateWithLifecycle()
    when (format) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LinearProgressIndicator() }
        "EPUB" -> EpubReaderScreen(onBack = onBack, onQuoteCard = onQuoteCard)
        else -> PdfReaderScreen(onBack = onBack, onQuoteCard = onQuoteCard)
    }
}
