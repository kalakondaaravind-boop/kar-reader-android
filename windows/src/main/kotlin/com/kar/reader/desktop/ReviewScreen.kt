package com.kar.reader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kar.reader.core.domain.CardSchedule
import com.kar.reader.core.domain.ReviewGrade
import com.kar.reader.core.domain.SpacedRepetition

private fun Card.schedule() = CardSchedule(dueAt, intervalMinutes, ease, reps, lapses)

@Composable
fun ReviewScreen(state: AppState) {
    var tab by remember { mutableStateOf("review") }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Title("Review", Modifier.weight(1f))
            Chip("Today's review", tab == "review") { tab = "review" }
            Chip("All cards (${state.data.cards.size})", tab == "cards") { tab = "cards" }
            PrimaryButton("New card", { adding = true })
        }
        if (tab == "review") DailyReview(state) else AllCards(state)
    }
    if (adding) CardDialog(state, null, "", "", { adding = false })
}

@Composable
private fun DailyReview(state: AppState) {
    var shown by remember { mutableStateOf(false) }
    var reviewed by remember { mutableIntStateOf(0) }
    val now = System.currentTimeMillis()
    val due = state.data.cards.filter { it.dueAt <= now }.sortedBy { it.dueAt }
    val card = due.firstOrNull()
    if (card == null) {
        Empty(
            if (state.data.cards.isEmpty()) "No flashcards yet" else "All caught up",
            if (state.data.cards.isEmpty()) "Make flashcards from your highlights (Flashcard button), or add your own. Spaced repetition brings each one back just before you'd forget it."
            else if (reviewed > 0) "You reviewed $reviewed card${if (reviewed == 1) "" else "s"} this session. Come back later for the next ones." else "Nothing is due right now.",
        )
        return
    }
    Muted("${due.size} due · $reviewed done this session")
    Panel(Modifier.fillMaxWidth().widthIn(max = 820.dp)) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            card.bookId?.let { id -> state.book(id)?.let { Muted(it.title, maxLines = 1) } }
            Text(card.front, style = MaterialTheme.typography.titleLarge)
            if (shown) {
                HorizontalDivider()
                Text(card.back, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (!shown) {
        PrimaryButton("Show answer", { shown = true })
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(ReviewGrade.AGAIN to "Again", ReviewGrade.HARD to "Hard", ReviewGrade.GOOD to "Good", ReviewGrade.EASY to "Easy").forEach { (g, label) ->
                val hint = SpacedRepetition.preview(card.schedule(), g, now)
                OutlinedButton(onClick = {
                    val next = SpacedRepetition.review(card.schedule(), g, now)
                    state.update { d -> d.copy(cards = d.cards.map { if (it.id == card.id) it.copy(dueAt = next.dueAt, intervalMinutes = next.intervalMinutes, ease = next.ease, reps = next.reps, lapses = next.lapses) else it }) }
                    shown = false; reviewed++
                }) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(label); Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
        }
    }
}

@Composable
private fun AllCards(state: AppState) {
    var deleting by remember { mutableStateOf<Card?>(null) }
    if (state.data.cards.isEmpty()) { Empty("No flashcards yet", "Use “New card”, or press Flashcard on any highlight."); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.data.cards.sortedBy { it.dueAt }, key = { it.id }) { c ->
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.front, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Muted(c.back, maxLines = 2)
                        Muted(if (c.dueAt <= System.currentTimeMillis()) "Due now" else "Next review ${formatDateTime(c.dueAt)}")
                    }
                    TextButton(onClick = { deleting = c }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    deleting?.let { c -> ConfirmDialog("Delete this flashcard?", "Its review history goes with it. This can't be undone.", "Delete", danger = true, onConfirm = { state.update { d -> d.copy(cards = d.cards.filter { it.id != c.id }) } }, onDismiss = { deleting = null }) }
}
