package com.kar.reader.ui.transcribe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kar.reader.core.designsystem.LocalKarStyle
import com.kar.reader.core.domain.WhisperLanguages
import com.kar.reader.data.db.TranscriptStatus
import com.kar.reader.ui.common.SwitchRow

/** Speaker colours. They differ in lightness as well as hue, and each is labelled with a name, never colour alone. */
val SpeakerColors = listOf(
    Color(0xFF1F4E8C), Color(0xFFA85612), Color(0xFF17665B), Color(0xFF7A3E9D),
    Color(0xFFB0303F), Color(0xFF4F6B12), Color(0xFF8A5A12), Color(0xFF2F6F9E),
)

@Composable
fun speakerColor(speaker: Int): Color = if (LocalKarStyle.current.eink) Color.Black else SpeakerColors[speaker.mod(SpeakerColors.size)]

/** Round avatar with the speaker's initial. */
@Composable
fun SpeakerDot(speaker: Int, name: String, size: Int = 36) {
    val c = speakerColor(speaker)
    val glossy = LocalKarStyle.current.glossy
    Box(
        Modifier.size(size.dp).background(if (glossy) Brush.verticalGradient(listOf(c.copy(alpha = 0.75f), c)) else Brush.verticalGradient(listOf(c, c)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "${speaker + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.42f).sp)
    }
}

fun statusLabel(status: String): String = when (status) {
    TranscriptStatus.RECORDING -> "Recording"
    TranscriptStatus.QUEUED -> "Waiting to start"
    TranscriptStatus.CONVERTING -> "Reading the audio"
    TranscriptStatus.SPEAKERS -> "Telling voices apart"
    TranscriptStatus.WORDS -> "Writing the words"
    TranscriptStatus.DONE -> "Ready"
    TranscriptStatus.FAILED -> "Didn't finish"
    TranscriptStatus.CANCELLED -> "Not transcribed yet"
    else -> status
}

/** The choices that shape a run: language, speaker separation and the number of people talking. */
@Composable
fun TranscribeOptionsFields(
    language: String,
    onLanguage: (String) -> Unit,
    separateSpeakers: Boolean,
    onSeparate: (Boolean) -> Unit,
    expectedSpeakers: Int,
    onExpected: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LanguagePicker(language, onLanguage)
        SwitchRow("Tell speakers apart", "Labels each voice as Speaker 1, 2, 3…", separateSpeakers, onSeparate)
        if (separateSpeakers) {
            Text("How many people are talking?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val choices = listOf(0 to "Not sure", 2 to "2", 3 to "3", 4 to "4", 5 to "5", 6 to "6+")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                choices.forEachIndexed { i, (n, label) ->
                    SegmentedButton(
                        selected = expectedSpeakers == n || (n == 6 && expectedSpeakers > 6),
                        onClick = { onExpected(n) },
                        shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                        icon = {},
                    ) { Text(label, maxLines = 1, fontSize = 13.sp) }
                }
            }
            Text("Giving the exact number makes the labels more accurate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LanguagePicker(language: String, onLanguage: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text("Language", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (language.isBlank()) "Automatic: English, Hindi, Hinglish" else WhisperLanguages.name(language), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                DropdownMenuItem(text = { Text("Automatic: English, Hindi, Hinglish", fontWeight = FontWeight.SemiBold) }, onClick = { onLanguage(""); open = false })
                WhisperLanguages.all.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { onLanguage(code); open = false })
                }
            }
        }
        Text(
            "Automatic recognizes English, Hindi and Hinglish. For any other language, pick it here: it is then " +
                "transcribed with a model for 1600+ languages.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun BookPicker(books: List<Pair<String, String>>, selected: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text("Link to a book (optional)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), enabled = books.isNotEmpty()) {
                Text(books.firstOrNull { it.first == selected }?.second ?: "None", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                DropdownMenuItem(text = { Text("None") }, onClick = { onSelect(null); open = false })
                books.forEach { (id, title) -> DropdownMenuItem(text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { onSelect(id); open = false }) }
            }
        }
    }
}

@Composable
fun TitleField(title: String, onTitle: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = title, onValueChange = { onTitle(it.take(120)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Title") }, placeholder = { Text(placeholder, maxLines = 1) },
    )
}

@Composable
fun StatusChip(status: String) {
    val (bg, fg) = when (status) {
        TranscriptStatus.DONE -> Color(0xFFD5EBDB) to Color(0xFF17532B)
        TranscriptStatus.FAILED -> Color(0xFFFBE1DB) to Color(0xFF8E2412)
        TranscriptStatus.RECORDING -> Color(0xFFFFDAD1) to Color(0xFF7A1606)
        in TranscriptStatus.working -> Color(0xFFDCE5F0) to Color(0xFF16304F)
        else -> Color(0xFFEDE6D8) to Color(0xFF4F4636)
    }
    val eink = LocalKarStyle.current.eink
    Row(
        Modifier.background(if (eink) Color.White else bg, CircleShape).heightIn(min = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(statusLabel(status), color = if (eink) Color.Black else fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}


/** Devanagari or Roman letters for Hindi text. Applies to every transcript, its exports and notes, and can change any time. */
@Composable
fun HindiScriptPicker(roman: Boolean, onRoman: (Boolean) -> Unit) {
    Column {
        Text("Write Hindi in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !roman, onClick = { onRoman(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("देवनागरी Devanagari", maxLines = 1, fontSize = 13.sp) }
            SegmentedButton(selected = roman, onClick = { onRoman(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text("Roman (Hinglish)", maxLines = 1, fontSize = 13.sp) }
        }
        Text(
            if (roman) "Shown like “aaj ka topic kya hai”. You can switch back any time." else "Shown like “आज का टॉपिक क्या है”. You can switch any time.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
