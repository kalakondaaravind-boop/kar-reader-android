package com.kar.reader.ui.stats

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.core.designsystem.ProgressRing
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.data.repo.ReadingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FocusState(
    val minutes: Int = 25,
    val running: Boolean = false,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    val dndActive: Boolean = false,
    val finished: Boolean = false,
    val savedMessage: String? = null,
)

@HiltViewModel
class FocusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
    private val reading: ReadingRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(FocusState())
    val state: StateFlow<FocusState> = _state.asStateFlow()
    private var ticker: Job? = null
    private var startedAt = 0L
    private var previousFilter: Int? = null
    private val notifications = context.getSystemService(NotificationManager::class.java)

    fun canControlDnd(): Boolean = notifications?.isNotificationPolicyAccessGranted == true

    fun dndSettingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun setMinutes(m: Int) { if (!_state.value.running) _state.value = _state.value.copy(minutes = m) }

    fun start() {
        startedAt = System.currentTimeMillis()
        var dnd = false
        if (canControlDnd()) {
            runCatching {
                previousFilter = notifications?.currentInterruptionFilter
                notifications?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                dnd = true
            }
        }
        _state.value = _state.value.copy(running = true, paused = false, elapsedMs = 0, dndActive = dnd, finished = false, savedMessage = null)
        runTicker()
    }

    private fun runTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            var last = System.currentTimeMillis()
            while (isActive) {
                delay(1_000)
                val now = System.currentTimeMillis()
                val s = _state.value
                if (!s.paused) {
                    val elapsed = s.elapsedMs + (now - last)
                    _state.value = s.copy(elapsedMs = elapsed)
                    if (elapsed >= s.minutes * 60_000L) { finish(completed = true); return@launch }
                }
                last = now
            }
        }
    }

    fun togglePause() { _state.value = _state.value.copy(paused = !_state.value.paused) }

    /** Ends the session, restores Do Not Disturb, and records the time actually spent. */
    fun finish(completed: Boolean = false) {
        val s = _state.value
        if (!s.running) return
        ticker?.cancel()
        restoreDnd()
        val elapsed = s.elapsedMs
        _state.value = s.copy(running = false, paused = false, dndActive = false, finished = completed, savedMessage = null)
        viewModelScope.launch {
            reading.recordSession(profiles.currentIdNow(), null, startedAt, elapsed, focus = true)
            _state.value = _state.value.copy(
                savedMessage = if (elapsed >= 15_000) "${elapsed / 60_000} min added to your statistics" else "Sessions under 15 seconds aren't recorded",
            )
        }
    }

    private fun restoreDnd() {
        val prev = previousFilter ?: return
        previousFilter = null
        runCatching { if (canControlDnd()) notifications?.setInterruptionFilter(prev) }
    }

    override fun onCleared() {
        restoreDnd()
        super.onCleared()
    }
}

@Composable
fun FocusScreen(onBack: () -> Unit, vm: FocusViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler(enabled = state.running) { vm.finish() }
    DisposableEffect(Unit) { onDispose { if (vm.state.value.running) vm.finish() } }
    val total = state.minutes * 60_000L
    val remaining = (total - state.elapsedMs).coerceAtLeast(0)
    Column(Modifier.fillMaxSize()) {
        KarTopBar(title = "Focus session", subtitle = if (state.dndActive) "Do Not Disturb is on" else null, onBack = { if (state.running) vm.finish(); onBack() }, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                ProgressRing(if (state.running || state.finished) state.elapsedMs.toFloat() / total else 0f, Modifier.size(260.dp), stroke = 16.dp, trackColor = MaterialTheme.colorScheme.outlineVariant)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%d:%02d".format(remaining / 60_000, (remaining / 1000) % 60), fontSize = 54.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (state.running) (if (state.paused) "paused" else "left of ${state.minutes} min") else "minutes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!state.running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 25, 45, 60).forEach { m -> FilterChip(selected = state.minutes == m, onClick = { vm.setMinutes(m) }, label = { Text("$m min") }) }
                }
                if (!vm.canControlDnd()) {
                    GlassCard(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                        Text("Allow Do Not Disturb", fontWeight = FontWeight.SemiBold)
                        Text("To silence notifications during focus sessions, allow KAR READER to control Do Not Disturb. It's optional; the timer works either way.", fontSize = 13.sp)
                        TextButton(onClick = { context.startActivity(vm.dndSettingsIntent()) }) { Text("Open system settings") }
                    }
                }
                GlossyButton("Start focus session", onClick = vm::start, kind = GlossyKind.GOLD, modifier = Modifier.widthIn(min = 260.dp))
                state.savedMessage?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlossyButton(if (state.paused) "Resume" else "Pause", onClick = vm::togglePause, kind = GlossyKind.PEARL)
                    GlossyButton("End and save", onClick = { vm.finish() }, kind = GlossyKind.GOLD)
                }
                Text("Open a book from the library in split screen, or just read on paper. The timer keeps counting while this screen is open.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 520.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
