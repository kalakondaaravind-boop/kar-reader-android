package com.kar.reader.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.kar.reader.core.domain.ReadingStats
import com.kar.reader.core.domain.SessionFact
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun StatsScreen(state: AppState) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val facts = state.data.sessions.map { SessionFact(it.bookId, it.startedAt, it.durationMs, it.focus) }
    val days = (13 downTo 0).map { today.minusDays(it.toLong()) }
    val minutes = days.map { ReadingStats.minutesOn(it, facts, zone) }
    val recap = ReadingStats.yearRecap(
        year = today.year, sessions = facts, finishedAt = state.data.books.filter { it.finishedAt > 0 }.map { it.finishedAt },
        highlightsByBook = state.data.highlights.groupBy { it.bookId }.mapValues { (_, l) -> l.map { it.createdAt } },
        titleOf = { id -> state.book(id)?.title ?: "Unknown" }, zone = zone,
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("Statistics")
        Muted("Only reading time the app actually measured while a book was open (at least 20 seconds at a time) or a focus session you ran is counted.")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Stat("Current streak", "${ReadingStats.currentStreak(facts, today, zone)} days", Modifier.weight(1f))
            Stat("Best streak", "${ReadingStats.bestStreak(facts, zone)} days", Modifier.weight(1f))
            Stat("Read today", "${ReadingStats.minutesOn(today, facts, zone)} min", Modifier.weight(1f))
            Stat("Books finished", "${state.data.books.count { it.status == "finished" && it.deletedAt == 0L }}", Modifier.weight(1f))
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Minutes read, last 14 days", style = MaterialTheme.typography.titleMedium)
                val bar = MaterialTheme.colorScheme.primary
                val track = MaterialTheme.colorScheme.surfaceVariant
                Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                    val max = (minutes.maxOrNull() ?: 0).coerceAtLeast(10).toFloat()
                    val slot = size.width / minutes.size
                    minutes.forEachIndexed { i, m ->
                        drawRoundRect(track, Offset(i * slot + slot * 0.18f, 0f), Size(slot * 0.64f, size.height), CornerRadius(8f))
                        val h = size.height * (m / max)
                        if (m > 0) drawRoundRect(bar, Offset(i * slot + slot * 0.18f, size.height - h), Size(slot * 0.64f, h), CornerRadius(8f))
                    }
                }
                Row(Modifier.fillMaxWidth()) { days.forEach { Text("${it.dayOfMonth}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${recap.year} so far", style = MaterialTheme.typography.titleMedium)
                Text("${recap.booksFinished} books finished · ${"%.1f".format(recap.hoursRead)} hours read · ${recap.highlightsMade} highlights · best streak ${recap.bestStreakDays} days")
                if (recap.topBooks.isNotEmpty()) Muted("Most highlighted: " + recap.topBooks.joinToString("; ") { "${it.first} (${it.second})" })
            }
        }
        FocusTimer(state)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Panel(modifier) { Column(Modifier.padding(18.dp)) { Muted(label); Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) } }
}

@Composable
private fun FocusTimer(state: AppState) {
    var minutes by remember { mutableIntStateOf(25) }
    var bookId by remember { mutableStateOf(state.liveBooks.firstOrNull()?.id) }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(startedAt) {
        val s = startedAt ?: return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            if (now - s >= minutes * 60_000L) {
                state.update { it.copy(sessions = it.sessions + Session(bookId.orEmpty(), s, minutes * 60_000L, focus = true)) }
                state.toast("Focus session complete: $minutes minutes")
                startedAt = null
                return@LaunchedEffect
            }
            delay(500)
        }
    }
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Focus timer", style = MaterialTheme.typography.titleMedium)
            val s = startedAt
            if (s == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(15, 25, 45, 60).forEach { m -> Chip("$m min", minutes == m) { minutes = m } }
                    Box {
                        GhostButton("Book: ${state.book(bookId)?.title?.take(28) ?: "none"}", { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            state.liveBooks.forEach { b -> DropdownMenuItem(text = { Text(b.title, maxLines = 1) }, onClick = { bookId = b.id; menu = false }) }
                        }
                    }
                    PrimaryButton("Start", { startedAt = System.currentTimeMillis() }, enabled = bookId != null)
                }
                if (state.liveBooks.isEmpty()) Muted("Add a book to start a focus session.")
            } else {
                val left = (minutes * 60_000L - (now - s)).coerceAtLeast(0) / 1000
                Text("%02d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Muted("Reading ${state.book(bookId)?.title.orEmpty()}")
                GhostButton("Stop (discard)", { startedAt = null })
            }
        }
    }
}
