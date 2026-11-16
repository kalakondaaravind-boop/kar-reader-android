package com.kar.reader.ui.transcribe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.LocalKarStyle
import com.kar.reader.core.domain.TranscriptText
import com.kar.reader.feature.transcribe.RecordingState
import com.kar.reader.feature.transcribe.TranscriptionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecordingViewModel @Inject constructor(private val manager: TranscriptionManager) : ViewModel() {
    val recording: StateFlow<RecordingState?> = manager.recording
    fun pause(paused: Boolean) = manager.pauseRecording(paused)
    suspend fun mark() = manager.markMoment("Marked moment")
    suspend fun stop(): String? {
        val id = manager.recording.value?.transcriptId
        manager.stopRecording(transcribe = true)
        return id
    }
}

@Composable
fun RecordingScreen(onClose: () -> Unit, onStopped: (String) -> Unit, vm: RecordingViewModel = hiltViewModel()) {
    val r by vm.recording.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var stopping by remember { mutableStateOf(false) }
    val eink = LocalKarStyle.current.eink
    val fg = if (eink) Color.Black else Color(0xFFFFF6E4)
    val dim = if (eink) Color.Black else Color(0xFFB8C6DA)

    // Recording ended elsewhere (from the bin, or it failed): leave.
    LaunchedEffect(r == null) { if (r == null && !stopping) onClose() }
    val state = r ?: return

    Box(
        Modifier.fillMaxSize().background(
            if (eink) Brush.verticalGradient(listOf(Color.White, Color.White))
            else Brush.radialGradient(listOf(Color(0xFF233A5E), Color(0xFF121C2E), Color(0xFF080C14))),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().widthIn(max = 640.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.title, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1)
                val badge = if (state.paused) "PAUSED" else "REC"
                Text(
                    badge, color = if (eink) Color.Black else Color(0xFF3A0B05), fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    modifier = Modifier.background(if (eink) Color.White else if (state.paused) Color(0xFFE8E1D4) else Color(0xFFFFB4A3), RoundedCornerShape(12.dp))
                        .then(if (eink) Modifier.border(2.dp, Color.Black, RoundedCornerShape(12.dp)) else Modifier)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
            Spacer(Modifier.height(48.dp))
            Text(TranscriptText.clock(state.elapsedMs), color = if (eink) Color.Black else Color.White, fontFamily = FontFamily.Monospace, fontSize = 60.sp, fontWeight = FontWeight.Medium)
            Text(if (state.paused) "Paused — nothing is being recorded" else levelLabel(state.level), color = dim, fontSize = 13.sp)
            Spacer(Modifier.height(28.dp))
            LevelBars(state.levels, eink, Modifier.fillMaxWidth().widthIn(max = 640.dp).height(110.dp))
            if (state.marks.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth().widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("MARKED MOMENTS", color = dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    state.marks.takeLast(4).forEach { m ->
                        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = if (eink) 0f else 0.07f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                            Text(TranscriptText.clock(m.atMs), color = if (eink) Color.Black else Color(0xFFF4C268), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            Spacer(Modifier.size(12.dp))
                            Text(m.label, color = fg, fontSize = 14.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Speakers are labelled after you stop. You can switch apps or lock the screen — recording continues.",
                color = dim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp),
            )
            Spacer(Modifier.height(32.dp))
            Row(Modifier.fillMaxWidth().widthIn(max = 520.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                GlossyButton(if (state.paused) "Resume" else "Pause", onClick = { vm.pause(!state.paused) }, kind = GlossyKind.PEARL, icon = if (state.paused) Icons.Filled.Mic else Icons.Filled.Pause)
                FilledIconButton(
                    onClick = {
                        if (!stopping) {
                            stopping = true
                            scope.launch { val id = vm.stop(); if (id != null) onStopped(id) else onClose() }
                        }
                    },
                    modifier = Modifier.size(88.dp).semantics { contentDescription = "Stop and transcribe" },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (eink) Color.Black else Color(0xFFD9573A), contentColor = Color.White),
                ) { Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(40.dp)) }
                GlossyButton("Mark", onClick = { scope.launch { vm.mark() } }, kind = GlossyKind.GOLD, icon = Icons.Filled.Flag, enabled = !state.paused)
            }
            Spacer(Modifier.height(10.dp))
            Text(if (stopping) "Saving…" else "Stop to transcribe", color = dim, fontSize = 12.sp)
        }
    }
}

private fun levelLabel(level: Float): String = when {
    level < 0.02f -> "Microphone · very quiet — move closer to the speakers"
    level > 0.9f -> "Microphone · very loud"
    else -> "Microphone · good level"
}

@Composable
private fun LevelBars(levels: List<Float>, eink: Boolean, modifier: Modifier) {
    val bars = (List((48 - levels.size).coerceAtLeast(0)) { 0f } + levels).takeLast(48)
    Row(
        modifier.background(Color.White.copy(alpha = if (eink) 0f else 0.06f), RoundedCornerShape(22.dp))
            .then(if (eink) Modifier.border(2.dp, Color.Black, RoundedCornerShape(22.dp)) else Modifier)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bars.forEach { v ->
            Box(
                Modifier.weight(1f).fillMaxHeight((0.06f + v * 0.94f).coerceIn(0.06f, 1f))
                    .background(if (eink) Brush.verticalGradient(listOf(Color.Black, Color.Black)) else Brush.verticalGradient(listOf(Color(0xFFFCDC8E), Color(0xFFD6922A))), RoundedCornerShape(3.dp)),
            )
        }
    }
}
