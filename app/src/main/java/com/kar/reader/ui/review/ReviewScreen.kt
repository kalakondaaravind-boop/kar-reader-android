@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kar.reader.ui.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.EmptyState
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.GlossyProgress
import com.kar.reader.core.designsystem.LocalKarStyle
import com.kar.reader.core.designsystem.SectionLabel
import com.kar.reader.core.domain.ReviewGrade
import com.kar.reader.core.domain.ReadingStats
import com.kar.reader.core.domain.SessionFact
import com.kar.reader.data.db.BookEntity
import com.kar.reader.data.db.FlashcardEntity
import com.kar.reader.data.db.displayAuthor
import com.kar.reader.data.db.displayTitle
import com.kar.reader.data.repo.FlashcardRepository
import com.kar.reader.data.repo.LibraryRepository
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.data.repo.ReadingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class ReviewState(
    val due: List<FlashcardEntity> = emptyList(),
    val total: Int = 0,
    val reviewedToday: Int = 0,
    val books: Map<String, BookEntity> = emptyMap(),
    val streak: Int = 0,
    val loaded: Boolean = false,
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val flashcards: FlashcardRepository,
    library: LibraryRepository,
    reading: ReadingRepository,
) : ViewModel() {
    val state: StateFlow<ReviewState> = profiles.currentId.flatMapLatest { pid ->
        combine(flashcards.observeCards(pid), library.observeLibrary(pid), reading.observeSessions(pid)) { cards, lib, sessions ->
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            ReviewState(
                due = cards.filter { it.dueAt <= now }.sortedBy { it.dueAt },
                total = cards.size,
                reviewedToday = cards.count { it.reps > 0 && it.modifiedAt >= startOfDay },
                books = lib.associate { it.book.id to it.book },
                streak = ReadingStats.currentStreak(sessions.map { SessionFact(it.bookId.orEmpty(), it.startedAt, it.durationMs, it.focus) }, LocalDate.now(zone), zone),
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewState())

    fun grade(card: FlashcardEntity, grade: ReviewGrade) = viewModelScope.launch { flashcards.review(card, grade) }
    fun preview(card: FlashcardEntity, grade: ReviewGrade) = flashcards.preview(card, grade, System.currentTimeMillis())
}

@Composable
fun ReviewScreen(vm: ReviewViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var revealed by remember { mutableStateOf(false) }
    val card = state.due.firstOrNull()
    val glossy = LocalKarStyle.current.glossy
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            SectionLabel("Spaced repetition · offline")
            Text("Review", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            val sessionTotal = state.due.size + state.reviewedToday
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${state.due.size} due · ${state.reviewedToday} reviewed today", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.streak > 0) Text("${state.streak}-day reading streak", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
            }
            GlossyProgress(if (sessionTotal == 0) 1f else state.reviewedToday.toFloat() / sessionTotal, Modifier.fillMaxWidth().padding(vertical = 10.dp))
            Spacer(Modifier.height(12.dp))
            when {
                !state.loaded -> Unit
                card == null -> EmptyState(
                    if (state.total == 0) "No flashcards yet" else "All caught up",
                    if (state.total == 0) "Highlights you make become fill-in-the-gap cards, and saved words become word cards. Everything is scheduled on this device."
                    else "Come back later. Cards return just before you're likely to forget them.",
                )
                else -> {
                    val book = card.bookId?.let { state.books[it] }
                    val density = LocalDensity.current.density
                    val shape = RoundedCornerShape(28.dp)
                    AnimatedContent(targetState = card.id to revealed, label = "card") { (_, shown) ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 320.dp)
                                .graphicsLayer { if (glossy) { rotationX = 3f; cameraDistance = 16f * density } }
                                .then(if (glossy) Modifier.shadow(18.dp, shape) else Modifier)
                                .clip(shape)
                                .background(if (glossy) Brush.verticalGradient(listOf(Color(0xFFFFFDF7), Color(0xFFF4ECD8))) else Brush.verticalGradient(listOf(Color.White, Color.White)))
                                .clickable { revealed = true }
                                .padding(28.dp),
                        ) {
                            Text(
                                if (card.sourceType == "VOCABULARY") "WORD · DO YOU REMEMBER IT?" else "FILL IN THE GAP · FROM YOUR HIGHLIGHT",
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFF8A5A12),
                            )
                            Spacer(Modifier.height(18.dp))
                            Text(card.front, fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 34.sp, color = Color(0xFF2E2418))
                            Spacer(Modifier.height(20.dp))
                            if (shown) {
                                Text(card.back, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFF8A5A12))
                            } else {
                                Text("Tap to show the answer", color = Color(0xFF6B5A44), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.weight(1f, fill = false))
                            if (book != null) {
                                Spacer(Modifier.height(20.dp))
                                Text("${book.displayTitle}${book.displayAuthor?.let { " · $it" } ?: ""}", fontSize = 13.sp, color = Color(0xFF6B5A44))
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (!revealed) {
                        GlossyButton("Show answer", onClick = { revealed = true }, modifier = Modifier.fillMaxWidth(), kind = GlossyKind.GOLD)
                    } else {
                        Text("How well did you remember it?", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GradeButton("Again", vm.preview(card, ReviewGrade.AGAIN), Color(0xFFA8321F), Modifier.weight(1f)) { vm.grade(card, ReviewGrade.AGAIN); revealed = false }
                            GradeButton("Hard", vm.preview(card, ReviewGrade.HARD), Color(0xFFA86C15), Modifier.weight(1f)) { vm.grade(card, ReviewGrade.HARD); revealed = false }
                            GradeButton("Good", vm.preview(card, ReviewGrade.GOOD), Color(0xFF2E6B3A), Modifier.weight(1f)) { vm.grade(card, ReviewGrade.GOOD); revealed = false }
                            GradeButton("Easy", vm.preview(card, ReviewGrade.EASY), Color(0xFF1F3A5F), Modifier.weight(1f)) { vm.grade(card, ReviewGrade.EASY); revealed = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeButton(label: String, interval: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val glossy = LocalKarStyle.current.glossy
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .heightIn(min = 64.dp)
            .then(if (glossy) Modifier.shadow(6.dp, shape) else Modifier)
            .clip(shape)
            .background(if (glossy) Brush.verticalGradient(listOf(color.copy(alpha = 0.75f), color)) else Brush.verticalGradient(listOf(Color.White, Color.White)))
            .then(if (glossy) Modifier.background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.25f), 0.45f to Color.Transparent)) else Modifier)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = if (glossy) Color.White else Color.Black, fontWeight = FontWeight.Bold)
            Text(interval, color = if (glossy) Color.White.copy(alpha = 0.85f) else Color.Black, fontSize = 11.sp)
        }
    }
}
