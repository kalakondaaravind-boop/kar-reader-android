@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kar.reader.ui.stats

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.ConfirmDialog
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.KarColors
import com.kar.reader.core.designsystem.LocalKarStyle
import com.kar.reader.core.designsystem.ProgressRing
import com.kar.reader.core.designsystem.SectionLabel
import com.kar.reader.core.domain.ReadingStats
import com.kar.reader.core.domain.SessionFact
import com.kar.reader.core.domain.YearRecap
import com.kar.reader.data.db.displayTitle
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.repo.AnnotationRepository
import com.kar.reader.data.repo.LibraryRepository
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.data.repo.ReadingRepository
import com.kar.reader.feature.export.DocumentRenderers
import com.kar.reader.ui.common.Format
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
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class StatsState(
    val minutesToday: Int = 0,
    val goal: Int = 30,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val week: List<Pair<String, Int>> = emptyList(),
    val totalMs: Long = 0,
    val sessions: Int = 0,
    val focusSessions: Int = 0,
    val recap: YearRecap? = null,
    val loaded: Boolean = false,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val reading: ReadingRepository,
    library: LibraryRepository,
    annotations: AnnotationRepository,
    private val prefs: AppPreferences,
) : ViewModel() {
    val state: StateFlow<StatsState> = profiles.currentId.flatMapLatest { pid ->
        combine(reading.observeSessions(pid), library.observeLibrary(pid), annotations.observeAllHighlights(pid), prefs.dailyGoalMinutes(pid)) { sessions, lib, highlights, goal ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val facts = sessions.map { SessionFact(it.bookId.orEmpty(), it.startedAt, it.durationMs, it.focus) }
            val titles = lib.associate { it.book.id to it.book.displayTitle }
            StatsState(
                minutesToday = ReadingStats.minutesOn(today, facts, zone),
                goal = goal,
                streak = ReadingStats.currentStreak(facts, today, zone),
                bestStreak = ReadingStats.bestStreak(facts, zone),
                week = (6 downTo 0).map { d ->
                    val day = today.minusDays(d.toLong())
                    day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) to ReadingStats.minutesOn(day, facts, zone)
                },
                totalMs = sessions.sumOf { it.durationMs },
                sessions = sessions.size,
                focusSessions = sessions.count { it.focus },
                recap = ReadingStats.yearRecap(
                    year = today.year,
                    sessions = facts,
                    finishedAt = lib.mapNotNull { it.state?.finishedAt },
                    highlightsByBook = highlights.groupBy({ it.bookId }, { it.createdAt }),
                    titleOf = { titles[it] ?: "A book in the recycle bin" },
                    zone = zone,
                ),
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsState())

    fun setGoal(minutes: Int) = viewModelScope.launch { prefs.setDailyGoal(profiles.currentIdNow(), minutes) }
    fun clear() = viewModelScope.launch { reading.clearStatistics(profiles.currentIdNow()) }

    fun shareRecap(context: Context, recap: YearRecap) = viewModelScope.launch {
        val bmp = DocumentRenderers.yearRecapImage(recap, soFar = recap.year == LocalDate.now().year)
        DocumentRenderers.shareImage(context, bmp, "kar-reader-${recap.year}.png", "Share your year in reading")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(onStartFocus: () -> Unit, vm: StatsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val glossy = LocalKarStyle.current.glossy
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionLabel("Measured on this device")
        Text("Reading statistics", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GlassCard(Modifier.weight(1f).widthIn(min = 280.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
                        ProgressRing(state.minutesToday.toFloat() / state.goal.coerceAtLeast(1), Modifier.size(110.dp), trackColor = MaterialTheme.colorScheme.outlineVariant)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${state.minutesToday}", fontWeight = FontWeight.Bold, fontSize = 26.sp)
                            Text("of ${state.goal} min", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text("Today's goal", fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { vm.setGoal(state.goal - 5) }) { Text("− 5") }
                            Text("${state.goal} min")
                            TextButton(onClick = { vm.setGoal(state.goal + 5) }) { Text("+ 5") }
                        }
                        Text("Current streak: ${state.streak} day${if (state.streak == 1) "" else "s"}", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                        Text("Best streak: ${state.bestStreak} days", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            GlassCard(Modifier.weight(1f).widthIn(min = 280.dp)) {
                Text("Last 7 days", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                val max = (state.week.maxOfOrNull { it.second } ?: 0).coerceAtLeast(state.goal).coerceAtLeast(1)
                val barColors = if (glossy) listOf(Color(0xFF5A8AC8), KarColors.Navy) else listOf(Color.Black, Color.Black)
                Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    state.week.forEach { (day, minutes) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Canvas(Modifier.width(22.dp).height(100.dp)) {
                                val h = size.height * minutes / max
                                drawRoundRect(Color(0x22000000), Offset.Zero, size, CornerRadius(8f))
                                drawRoundRect(Brush.verticalGradient(barColors), Offset(0f, size.height - h), Size(size.width, h), CornerRadius(8f))
                            }
                            Text(day, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Focus session", fontWeight = FontWeight.SemiBold)
                    Text("A reading timer that can turn on Do Not Disturb and adds the time to your statistics.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                GlossyButton("Start", onClick = onStartFocus, kind = GlossyKind.GOLD, icon = Icons.Filled.Timer)
            }
        }

        state.recap?.let { recap ->
            YearRecapCard(recap, onShare = { vm.shareRecap(context, recap) })
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text("All time", fontWeight = FontWeight.SemiBold)
            Text("${Format.minutes(state.totalMs)} across ${state.sessions} sessions (${state.focusSessions} focus sessions)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Only time a book was actually open on screen is counted; sessions under 15 seconds are ignored. Statistics never leave this device.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { confirmClear = true }) { Text("Reset statistics…", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            "Reset reading statistics?", "This permanently deletes this profile's reading sessions, streaks and goals history. Books, notes and highlights are not affected.",
            "Reset", onConfirm = vm::clear, onDismiss = { confirmClear = false }, destructive = true,
        )
    }
}

@Composable
private fun YearRecapCard(recap: YearRecap, onShare: () -> Unit) {
    val glossy = LocalKarStyle.current.glossy
    val tiles = listOf(
        Triple(recap.booksFinished.toString(), "books finished", listOf(Color(0xFFFCE19A), Color(0xFFA86C15))),
        Triple("%.0f".format(recap.hoursRead), "hours read", listOf(Color(0xFF9CC2F2), Color(0xFF142A47))),
        Triple(recap.bestStreakDays.toString(), "day best streak", listOf(Color(0xFFA9DDB4), Color(0xFF1E5A33))),
        Triple(recap.highlightsMade.toString(), "highlights made", listOf(Color(0xFFE3B6F0), Color(0xFF3E1F52))),
    )
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Your year in reading")
                Text("${recap.year}, so far", style = MaterialTheme.typography.headlineSmall)
            }
            GlossyButton("Share", onClick = onShare, kind = GlossyKind.PEARL, icon = Icons.Filled.Share, compact = true)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.forEachIndexed { i, (value, label, colors) ->
                val shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
                Column(
                    Modifier.weight(1f)
                        .then(if (glossy) Modifier.shadow(6.dp, shape) else Modifier)
                        .background(if (glossy) Brush.linearGradient(colors) else Brush.linearGradient(listOf(Color.White, Color.White)), shape)
                        .padding(14.dp),
                ) {
                    val dark = i == 0 || i == 2
                    val fg = if (!glossy) Color.Black else if (dark) Color(0xFF1A1206) else Color.White
                    Text(value, color = fg, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                    Text(label, color = fg.copy(alpha = 0.85f), fontSize = 12.sp)
                }
            }
        }
        if (recap.topBooks.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Most highlighted", fontWeight = FontWeight.SemiBold)
            recap.topBooks.forEach { (title, count) -> Text("$title · $count", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Made on this device. Never uploaded.", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
    }
}
