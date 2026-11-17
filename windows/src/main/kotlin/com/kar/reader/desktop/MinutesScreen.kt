package com.kar.reader.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.Toolkit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Puts formatted minutes on the clipboard so they paste into an email with the tables intact. */
private class HtmlSelection(private val html: String, private val plain: String) : Transferable {
    private val htmlFlavor = DataFlavor("text/html;class=java.lang.String")
    override fun getTransferDataFlavors() = arrayOf(htmlFlavor, DataFlavor.stringFlavor)
    override fun isDataFlavorSupported(f: DataFlavor) = f == htmlFlavor || f == DataFlavor.stringFlavor
    override fun getTransferData(f: DataFlavor): Any = if (f == htmlFlavor) html else plain
}

private fun parseWhen(text: String): Long? = listOf("d MMM yyyy, hh:mm a", "d MMM yyyy, HH:mm", "d MMM yyyy").firstNotNullOfOrNull { f ->
    runCatching { SimpleDateFormat(f, Locale.ENGLISH).apply { isLenient = false }.parse(text.trim())?.time }.getOrNull()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MinutesScreen(state: AppState, nav: Nav, id: String) {
    val meta = state.data.transcripts.firstOrNull { it.id == id }
    if (meta == null) { LaunchedEffect(Unit) { nav.go(Screen.Transcribe) }; return }
    val settings = state.data.settings
    var m by remember(id) {
        mutableStateOf(state.data.minutes.firstOrNull { it.transcriptId == id } ?: MinutesBuilder.draft(meta, TranscriptFiles.load(id), settings))
    }
    var whenText by remember(id) { mutableStateOf(MinutesFormat.dayTime(m.startedAt)) }
    var attendeesText by remember(id) { mutableStateOf(m.attendees.joinToString(", ")) }
    var agendaText by remember(id) { mutableStateOf(m.agenda.joinToString("\n")) }
    var decisionsText by remember(id) { mutableStateOf(m.decisions.joinToString("\n")) }
    var redo by remember { mutableStateOf(false) }
    var teams by remember { mutableStateOf(false) }

    fun change(next: Minutes) { m = next.copy(edited = true) }
    // Save a moment after the last keystroke, so typing doesn't rewrite the data file every character.
    LaunchedEffect(m) {
        delay(700)
        state.update { d -> d.copy(minutes = d.minutes.filter { it.transcriptId != id } + m) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { nav.go(Screen.Transcript(id)) }) { Text("← Transcript") }
            Title("Minutes of meeting", Modifier.weight(1f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Draft again", { if (m.edited) redo = true else {
                val d = MinutesBuilder.draft(meta, TranscriptFiles.load(id), settings); m = d
                whenText = MinutesFormat.dayTime(d.startedAt); attendeesText = d.attendees.joinToString(", "); agendaText = d.agenda.joinToString("\n"); decisionsText = d.decisions.joinToString("\n")
            } })
            GhostButton("From Teams calendar", { teams = true })
            GhostButton("Copy for email", {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(HtmlSelection(MinutesExport.html(m), MinutesExport.text(m)), null)
                state.toast("Copied. Paste it into your email.")
            })
            Box {
                var menu by remember { mutableStateOf(false) }
                PrimaryButton("Save as ▾", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf("doc" to "Word (.doc)", "html" to "Web page (.html)", "txt" to "Text (.txt)").forEach { (ext, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            menu = false
                            val f = saveFileDialog("Save minutes", "${slug(m.meeting)}-minutes.$ext") ?: return@DropdownMenuItem
                            val body = when (ext) { "doc" -> MinutesExport.html(m, forWord = true); "html" -> MinutesExport.page(m); else -> MinutesExport.text(m) }
                            File(if (f.name.contains('.')) f.path else f.path + "." + ext).writeText(body, Charsets.UTF_8)
                            state.toast("Saved ${f.name}")
                        })
                    }
                }
            }
        }
        Muted("This is a first draft written by simple rules from the transcript — there is no AI model on this computer. Please read it, fix names, dates and owners, then send.")

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(m.meeting, { change(m.copy(meeting = it)) }, label = { Text("Meeting") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(whenText, { whenText = it; parseWhen(it)?.let { ms -> change(m.copy(startedAt = ms)) } }, label = { Text("Date & time  (e.g. 11 Nov 2026, 04:00 PM)") }, singleLine = true, modifier = Modifier.weight(1f),
                            isError = parseWhen(whenText) == null)
                        OutlinedTextField((m.durationMs / 60_000).toString(), { v -> v.filter { it.isDigit() }.take(4).toLongOrNull()?.let { change(m.copy(durationMs = it * 60_000)) } }, label = { Text("Minutes") }, singleLine = true, modifier = Modifier.width(110.dp))
                    }
                    OutlinedTextField(m.project, { change(m.copy(project = it)) }, label = { Text("Project") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(attendeesText, { attendeesText = it; change(m.copy(attendees = it.split(',').map { x -> x.trim() }.filter { x -> x.isNotEmpty() })) }, label = { Text("Attendees  (separate with commas)") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(m.upper, { change(m.copy(upper = it)) })
                        Text("Write the minutes in capital letters, like the sample")
                    }
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(agendaText, { agendaText = it; change(m.copy(agenda = it.lines().map { x -> x.trim() }.filter { x -> x.isNotEmpty() })) }, label = { Text("Agenda  (one item per line)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(m.discussion, { change(m.copy(discussion = it)) }, label = { Text("Discussion") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(decisionsText, { decisionsText = it; change(m.copy(decisions = it.lines().map { x -> x.trim() }.filter { x -> x.isNotEmpty() })) }, label = { Text("Key decisions  (one per line)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Action points", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        GhostButton("+ Add action point", { change(m.copy(actions = m.actions + ActionPoint())) })
                    }
                    if (m.actions.isEmpty()) Muted("No action points found. Add one, or edit the transcript and draft again.")
                    m.actions.forEachIndexed { i, a ->
                        fun set(n: ActionPoint) = change(m.copy(actions = m.actions.mapIndexed { j, x -> if (j == i) n else x }))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                Text("${i + 1}", Modifier.width(24.dp).padding(top = 18.dp))
                                OutlinedTextField(a.text, { set(a.copy(text = it)) }, label = { Text("Action point") }, minLines = 2, modifier = Modifier.weight(3f))
                                OutlinedTextField(a.owner, { set(a.copy(owner = it)) }, label = { Text("Responsibility") }, singleLine = true, modifier = Modifier.weight(1.2f))
                                OutlinedTextField(a.target, { set(a.copy(target = it)) }, label = { Text("Target date") }, placeholder = { Text("–") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width(24.dp))
                                listOf("Open", "Initiated", "Closed").forEach { st -> Chip(st, a.status.equals(st, true)) { set(a.copy(status = st)) } }
                                OutlinedTextField(a.notes, { set(a.copy(notes = it)) }, label = { Text("Notes") }, singleLine = true, modifier = Modifier.weight(1f))
                                TextButton(onClick = { change(m.copy(actions = m.actions.filterIndexed { j, _ -> j != i })) }) { Text("Remove") }
                            }
                        }
                        if (i < m.actions.lastIndex) HorizontalDivider()
                    }
                }
            }
            Muted("Status colours when sent: Due today (amber), Initiated (blue), Open (grey), Closed/Complete (green).")
        }
    }
    if (teams) TeamsMeetingsDialog(state, onPick = { mt ->
        change(m.copy(meeting = mt.subject, startedAt = mt.startMs, durationMs = (mt.endMs - mt.startMs).coerceAtLeast(0), attendees = mt.attendees.ifEmpty { m.attendees }))
        whenText = MinutesFormat.dayTime(mt.startMs); attendeesText = (mt.attendees.ifEmpty { m.attendees }).joinToString(", ")
        teams = false
    }, onDismiss = { teams = false })
    if (redo) ConfirmDialog(
        "Draft again?", "Your edits to these minutes will be replaced by a new draft from the transcript.", "Replace my edits", danger = true,
        onConfirm = {
            val d = MinutesBuilder.draft(meta, TranscriptFiles.load(id), settings); m = d
            whenText = MinutesFormat.dayTime(d.startedAt); attendeesText = d.attendees.joinToString(", "); agendaText = d.agenda.joinToString("\n"); decisionsText = d.decisions.joinToString("\n")
            redo = false
        },
        onDismiss = { redo = false },
    )
}
