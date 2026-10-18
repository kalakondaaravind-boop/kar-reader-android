@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kar.reader.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.BookCover3D
import com.kar.reader.core.designsystem.EmptyState
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.GlossyProgress
import com.kar.reader.core.designsystem.HeroPanel
import com.kar.reader.core.designsystem.KarColors
import com.kar.reader.core.designsystem.LocalKarStyle
import com.kar.reader.core.designsystem.ProgressRing
import com.kar.reader.core.designsystem.SectionLabel
import com.kar.reader.core.domain.ReadingStats
import com.kar.reader.core.domain.SessionFact
import com.kar.reader.core.lock.AppLockManager
import com.kar.reader.data.db.displayAuthor
import com.kar.reader.data.db.displayTitle
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.repo.FlashcardRepository
import com.kar.reader.data.repo.LibraryItem
import com.kar.reader.data.repo.LibraryRepository
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.data.repo.ReadingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class HomeState(
    val profileName: String = "",
    val recent: List<LibraryItem> = emptyList(),
    val bookCount: Int = 0,
    val minutesToday: Int = 0,
    val goal: Int = 30,
    val streak: Int = 0,
    val cardsDue: Int = 0,
    val loaded: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    profiles: ProfileRepository,
    library: LibraryRepository,
    reading: ReadingRepository,
    flashcards: FlashcardRepository,
    prefs: AppPreferences,
    lock: AppLockManager,
) : ViewModel() {
    val state: StateFlow<HomeState> = profiles.current.flatMapLatest { profile ->
        val zone = ZoneId.systemDefault()
        combine(
            library.observeLibrary(profile.id),
            reading.observeSessions(profile.id),
            flashcards.observeCards(profile.id),
            prefs.dailyGoalMinutes(profile.id),
            combine(prefs.settings, lock.privateUnlocked) { s, unlocked -> s.hidePrivateFromRecent && !unlocked },
        ) { items, sessions, cards, goal, hidePrivate ->
            val facts = sessions.map { SessionFact(it.bookId.orEmpty(), it.startedAt, it.durationMs, it.focus) }
            val today = LocalDate.now(zone)
            val now = System.currentTimeMillis()
            HomeState(
                profileName = profile.name,
                recent = items.filter { it.lastOpenedAt != null && !(hidePrivate && it.isPrivate) }.sortedByDescending { it.lastOpenedAt }.take(10),
                bookCount = items.count { !(it.isPrivate && hidePrivate) },
                minutesToday = ReadingStats.minutesOn(today, facts, zone),
                goal = goal,
                streak = ReadingStats.currentStreak(facts, today, zone),
                cardsDue = cards.count { it.dueAt <= now },
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenBook: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenReview: () -> Unit,
    onStartFocus: () -> Unit,
    onOpenStats: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val glossy = LocalKarStyle.current.glossy
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column {
            SectionLabel("KAR READER")
            Text(
                if (state.profileName.isBlank() || state.profileName == "Me") "Welcome back" else "Welcome back, ${state.profileName}",
                style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary,
            )
        }

        val current = state.recent.firstOrNull()
        if (current != null) {
            HeroPanel(Modifier.fillMaxWidth().clickable { onOpenBook(current.book.id) }) {
                BookCover3D(current.book.displayTitle, current.book.format, current.coverFile, Modifier.width(76.dp))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text("CONTINUE READING", style = MaterialTheme.typography.labelSmall, color = if (glossy) Color(0xFFF4C268) else Color.Black)
                    Text(
                        current.book.displayTitle, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp,
                        color = if (glossy) Color.White else Color.Black, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(current.book.displayAuthor, "${current.progressPercent}%").joinToString(" · "),
                        color = if (glossy) Color(0xFFB8C6DA) else Color.Black, fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    GlossyProgress(current.progressPercent / 100f, Modifier.fillMaxWidth(), onDark = true)
                }
                Spacer(Modifier.width(16.dp))
                GlossyButton("Resume", onClick = { onOpenBook(current.book.id) }, kind = GlossyKind.GOLD, icon = Icons.Filled.PlayArrow)
            }
        } else if (state.loaded) {
            GlassCard(Modifier.fillMaxWidth()) {
                EmptyState(
                    title = if (state.bookCount == 0) "Your library is empty" else "Pick something to read",
                    message = if (state.bookCount == 0) "Import PDF or EPUB files, a ZIP of books, a Calibre library, or scan paper pages. Everything stays on this device."
                    else "Open any book in your library and it will appear here.",
                    action = { GlossyButton("Open library", onClick = onOpenLibrary, icon = Icons.AutoMirrored.Filled.LibraryBooks) },
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), maxItemsInEachRow = 3) {
            GlassCard(Modifier.weight(1f).widthIn(min = 220.dp), onClick = onOpenStats) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                        ProgressRing(state.minutesToday.toFloat() / state.goal.coerceAtLeast(1), Modifier.size(84.dp), stroke = 10.dp, trackColor = MaterialTheme.colorScheme.outlineVariant)
                        Text("${state.minutesToday}", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Today's goal", fontWeight = FontWeight.SemiBold)
                        Text("${state.minutesToday} of ${state.goal} min", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (state.streak == 1) "1-day streak" else "${state.streak}-day streak", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            GlassCard(Modifier.weight(1f).widthIn(min = 220.dp), onClick = onOpenReview) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.School, contentDescription = null, tint = KarColors.Green, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("${state.cardsDue} flashcards due", fontWeight = FontWeight.SemiBold)
                        Text(if (state.cardsDue == 0) "All caught up" else "Made from your highlights", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            GlassCard(Modifier.weight(1f).widthIn(min = 220.dp), onClick = onStartFocus) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Start a focus session", fontWeight = FontWeight.SemiBold)
                        Text("25 min · Do Not Disturb", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (state.recent.size > 1) {
            SectionLabel("Recently opened")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(state.recent.drop(1), key = { it.book.id }) { item ->
                    Column(Modifier.width(120.dp).clickable { onOpenBook(item.book.id) }) {
                        BookCover3D(item.book.displayTitle, item.book.format, item.coverFile, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(item.book.displayTitle, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        GlossyProgress(item.progressPercent / 100f, Modifier.fillMaxWidth().padding(top = 4.dp), height = 5.dp)
                    }
                }
            }
        }
    }
}
