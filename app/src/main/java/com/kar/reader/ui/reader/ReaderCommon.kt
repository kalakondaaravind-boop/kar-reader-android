@file:OptIn(ExperimentalMaterial3Api::class)

package com.kar.reader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.GlossyOrb
import com.kar.reader.core.domain.CitationSource
import com.kar.reader.core.domain.CitationStyle
import com.kar.reader.core.domain.Citations
import com.kar.reader.core.model.HighlightColor
import com.kar.reader.data.db.BookEntity
import com.kar.reader.data.db.displayAuthor
import com.kar.reader.data.db.displayTitle

fun BookEntity.citationSource(): CitationSource = CitationSource(
    authors = displayAuthor?.split(Regex("\\s*(,|&| and )\\s*"))?.filter { it.isNotBlank() }.orEmpty(),
    title = displayTitle,
    year = year,
    publisher = publisher,
)

/** Citation panel: pick a style, copy the reference or the quote with its citation. */
@Composable
fun CitationSheet(book: BookEntity, quote: String?, location: String?, initialStyle: CitationStyle, onStyleChange: (CitationStyle) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    var style by remember { mutableStateOf(initialStyle) }
    var copied by remember { mutableStateOf<String?>(null) }
    val source = book.citationSource()
    val reference = Citations.bibliography(source, style)
    val inText = Citations.inText(source, style, location)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Cite this", style = MaterialTheme.typography.headlineSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                CitationStyle.entries.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = style == s,
                        onClick = { style = s; onStyleChange(s) },
                        shape = SegmentedButtonDefaults.itemShape(i, CitationStyle.entries.size),
                    ) { Text(s.label) }
                }
            }
            if (quote != null) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("“${quote.trim()}” $inText", fontFamily = FontFamily.Serif, fontSize = 15.sp, fontStyle = FontStyle.Italic)
                }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Reference", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(6.dp))
                Text(reference, fontFamily = FontFamily.Serif, fontSize = 15.sp)
            }
            if (book.year == null || book.publisher == null) {
                Text(
                    "Add the ${listOfNotNull(if (book.year == null) "year" else null, if (book.publisher == null) "publisher" else null).joinToString(" and ")} in the book's details for a complete reference.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (location != null && location.none(Char::isDigit) && book.format == "EPUB") {
                Text("This EPUB has no fixed page numbers, so the chapter is used as the location.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlossyButton("Copy reference", onClick = { clipboard.setText(AnnotatedString(reference)); copied = "Reference copied" }, kind = GlossyKind.PEARL, icon = Icons.Filled.ContentCopy, modifier = Modifier.weight(1f))
                if (quote != null) {
                    GlossyButton("Copy quote", onClick = { clipboard.setText(AnnotatedString(Citations.quoteWithCitation(quote, source, style, location))); copied = "Quote and citation copied" }, icon = Icons.Filled.ContentCopy, modifier = Modifier.weight(1f))
                }
            }
            copied?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun QuickNoteDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().height(180.dp), placeholder = { Text("Write a note") }) },
        confirmButton = { TextButton(onClick = { onSave(text); onDismiss() }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Row of highlight colours with their meanings, e.g. "Yellow · Key idea". */
@Composable
fun HighlightColorRow(selected: HighlightColor?, meanings: Map<HighlightColor, String>, onPick: (HighlightColor) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        HighlightColor.entries.forEach { c ->
            GlossyOrb(
                Color(c.argb),
                Modifier.size(34.dp).clickable { onPick(c) }.semantics { contentDescription = "${c.name.lowercase()}: ${meanings[c] ?: c.defaultMeaning}" },
                selected = selected == c,
            )
        }
    }
}

@Composable
fun HighlightColorDialog(text: String, meanings: Map<HighlightColor, String>, onPick: (HighlightColor) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Highlight") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("“${text.take(240)}${if (text.length > 240) "…" else ""}”", fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                HighlightColor.entries.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(c); onDismiss() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlossyOrb(Color(c.argb), Modifier.size(26.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(meanings[c] ?: c.defaultMeaning)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
