package com.kar.reader.ui.share

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.GlossyOrb
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.data.db.HighlightDao
import com.kar.reader.data.db.displayAuthor
import com.kar.reader.data.db.displayTitle
import com.kar.reader.data.repo.LibraryRepository
import com.kar.reader.feature.epub.EpubSession
import com.kar.reader.feature.export.DocumentRenderers
import com.kar.reader.feature.export.QuoteCardStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class QuoteInfo(val quote: String, val author: String, val source: String)

@HiltViewModel
class QuoteCardViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val highlights: HighlightDao,
    private val library: LibraryRepository,
) : ViewModel() {
    private val highlightId: String = checkNotNull(savedState.get<String>("highlightId"))

    suspend fun load(): QuoteInfo? {
        val h = highlights.get(highlightId) ?: return null
        val book = library.getBook(h.bookId) ?: return null
        val where = if (book.format == "PDF") "Page ${h.position + 1}" else withContext(Dispatchers.IO) {
            runCatching { EpubSession.open(library.fileFor(book)).use { it.chapterLabel(h.position) } }.getOrDefault("Chapter ${h.position + 1}")
        }
        return QuoteInfo(h.text, book.displayAuthor ?: book.displayTitle, "${book.displayTitle} · $where")
    }

    fun share(context: Context, bitmap: Bitmap) = viewModelScope.launch {
        DocumentRenderers.shareImage(context, bitmap, "quote-${System.currentTimeMillis()}.png", "Share quote card")
    }
}

@Composable
fun QuoteCardScreen(onBack: () -> Unit, vm: QuoteCardViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<QuoteInfo?>(null) }
    var missing by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(QuoteCardStyle.NAVY) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(Unit) { info = vm.load(); missing = info == null }
    LaunchedEffect(info, style) {
        val i = info ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.Default) { DocumentRenderers.quoteCard(i.quote, i.author, i.source, style) }
    }
    Column(Modifier.fillMaxSize()) {
        KarTopBar(title = "Quote card", onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (missing) Text("This highlight is no longer available.")
            bitmap?.let {
                Image(
                    it.asImageBitmap(), contentDescription = "Quote card preview",
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().shadow(18.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)),
                )
            }
            Text("Card style", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                QuoteCardStyle.entries.forEach { s ->
                    GlossyOrb(Color(s.top), Modifier.size(44.dp).clickable { style = s }, selected = style == s)
                }
            }
            GlossyButton("Share image…", onClick = { bitmap?.let { vm.share(context, it) } }, kind = GlossyKind.GOLD, icon = Icons.Filled.Share, enabled = bitmap != null, modifier = Modifier.widthIn(min = 260.dp))
            Text("Sent through Android's share menu. KAR READER itself never goes online.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
