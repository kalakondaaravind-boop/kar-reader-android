package com.kar.reader.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

class Hit(val bookId: String, val title: String, val unit: Int, val label: String, val snippet: String, val start: Int = -1, val end: Int = -1)

fun snippetAround(text: String, query: String, radius: Int = 70): String? {
    val i = text.indexOf(query, ignoreCase = true)
    if (i < 0) return null
    val from = (i - radius).coerceAtLeast(0)
    val to = (i + query.length + radius).coerceAtMost(text.length)
    return (if (from > 0) "…" else "") + text.substring(from, to).replace(Regex("\\s+"), " ").trim() + (if (to < text.length) "…" else "")
}

/** Searches the text of every book in the library. Runs on this computer only; nothing is indexed on disk. */
@Composable
fun FullTextSearchDialog(state: AppState, onOpen: (String, Int) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    val hits = remember { mutableStateListOf<Hit>() }
    LaunchedEffect(submitted) {
        hits.clear()
        val q = submitted.trim()
        if (q.length < 2) { progress = ""; return@LaunchedEffect }
        val books = state.liveBooks
        withContext(Dispatchers.IO) {
            books.forEachIndexed { index, b ->
                if (!isActive || hits.size >= 300) return@forEachIndexed
                progress = "Searching book ${index + 1} of ${books.size}: ${b.title}"
                runCatching {
                    val file = File(Paths.books, b.file)
                    if (b.isPdf) PdfBook(file).use { pdf ->
                        for (p in 0 until pdf.pageCount) {
                            if (!isActive || hits.size >= 300) break
                            snippetAround(pdf.text(p), q)?.let { hits += Hit(b.id, b.title, p, "p. ${p + 1}", it) }
                        }
                    } else EpubBook(file).use { epub ->
                        for (c in epub.chapters.indices) {
                            if (!isActive || hits.size >= 300) break
                            snippetAround(epub.text(c), q)?.let { hits += Hit(b.id, b.title, c, epub.chapters[c].title, it) }
                        }
                    }
                }
            }
        }
        progress = if (hits.isEmpty()) "No matches." else "${hits.size}${if (hits.size >= 300) "+" else ""} matches"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(760.dp),
        title = { Text("Search inside all books") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("Word or phrase") })
                    PrimaryButton("Search", { submitted = input })
                }
                Muted(progress, maxLines = 1)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(hits.toList()) { h ->
                        Column(Modifier.fillMaxWidth().clickable { onOpen(h.bookId, h.unit) }.padding(vertical = 8.dp)) {
                            Text("${h.title} · ${h.label}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted(h.snippet, maxLines = 2)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
