package com.kar.reader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import javax.swing.UIManager

sealed interface Screen {
    data object Library : Screen
    data class Reader(val bookId: String) : Screen
    data object Notes : Screen
    data object Review : Screen
    data object Stats : Screen
    data object Transcribe : Screen
    data class Transcript(val id: String) : Screen
    data object Recording : Screen
    data object Bin : Screen
    data object Settings : Screen
}

class Nav {
    var current by mutableStateOf<Screen>(Screen.Library)
    fun go(s: Screen) { current = s }
}

/** Lets automated checks open a given screen (KAR_START=library|reader|reader-epub|notes|review|stats|transcribe|transcript|bin|settings). */
private fun startScreen(nav: Nav, state: AppState) {
    when (System.getenv("KAR_START")) {
        "reader" -> state.liveBooks.firstOrNull { it.isPdf }?.let { nav.go(Screen.Reader(it.id)) }
        "reader-epub" -> state.liveBooks.firstOrNull { !it.isPdf }?.let { nav.go(Screen.Reader(it.id)) }
        "notes" -> nav.go(Screen.Notes)
        "review" -> nav.go(Screen.Review)
        "stats" -> nav.go(Screen.Stats)
        "transcribe" -> nav.go(Screen.Transcribe)
        "transcript" -> state.data.transcripts.firstOrNull { it.status == "done" }?.let { nav.go(Screen.Transcript(it.id)) }
        "bin" -> nav.go(Screen.Bin)
        "settings" -> nav.go(Screen.Settings)
    }
}

fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    application {
        val state = remember { AppState() }
        val nav = remember { Nav().also { startScreen(it, state) } }
        val jobs = remember { TranscribeJobs(state) }
        val size = System.getenv("KAR_WINDOW")?.split('x')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }
        val window = rememberWindowState(size = if (size != null) DpSize(size[0].dp, size[1].dp) else DpSize(1360.dp, 860.dp))
        Window(
            onCloseRequest = { jobs.cancelAll(); exitApplication() },
            title = "KAR READER",
            state = window,
            icon = painterResource("icon.png"),
            onPreviewKeyEvent = { e ->
                if (e.type == KeyEventType.KeyDown && e.isCtrlPressed) {
                    val target = when (e.key) {
                        Key.One -> Screen.Library; Key.Two -> Screen.Notes; Key.Three -> Screen.Review; Key.Four -> Screen.Stats
                        Key.Five -> Screen.Transcribe; Key.Six -> Screen.Bin; Key.Comma -> Screen.Settings; else -> null
                    }
                    if (target != null) { nav.go(target); true } else false
                } else false
            },
        ) {
            KarTheme(state.data.settings.theme) { AppRoot(state, nav, jobs) }
        }
    }
}

@Composable
fun AppRoot(state: AppState, nav: Nav, jobs: TranscribeJobs) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize()) {
            val s = nav.current
            if (s !is Screen.Reader) Sidebar(state, nav, jobs)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (s) {
                    Screen.Library -> LibraryScreen(state, nav)
                    is Screen.Reader -> ReaderScreen(state, nav, s.bookId)
                    Screen.Notes -> NotesScreen(state, nav)
                    Screen.Review -> ReviewScreen(state)
                    Screen.Stats -> StatsScreen(state)
                    Screen.Transcribe -> TranscribeScreen(state, nav, jobs)
                    is Screen.Transcript -> TranscriptScreen(state, nav, s.id)
                    Screen.Recording -> RecordingScreen(state, nav, jobs)
                    Screen.Bin -> BinScreen(state)
                    Screen.Settings -> SettingsScreen(state)
                }
                val msg = state.message
                if (msg != null) {
                    LaunchedEffect(msg) { delay(5000); if (state.message == msg) state.message = null }
                    Box(Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp) {
                            Text(msg, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(state: AppState, nav: Nav, jobs: TranscribeJobs) {
    val due = state.data.cards.count { it.dueAt <= System.currentTimeMillis() }
    Column(
        Modifier.width(224.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(KarColors.Primary), contentAlignment = Alignment.Center) { Text("K", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleMedium) }
            Column {
                Text("KAR READER", style = MaterialTheme.typography.titleMedium)
                Text("Offline · private", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        NavItem("Library", "Ctrl+1", nav.current is Screen.Library) { nav.go(Screen.Library) }
        NavItem("Notes & highlights", "Ctrl+2", nav.current is Screen.Notes) { nav.go(Screen.Notes) }
        NavItem(if (due > 0) "Review  ($due due)" else "Review", "Ctrl+3", nav.current is Screen.Review) { nav.go(Screen.Review) }
        NavItem("Statistics", "Ctrl+4", nav.current is Screen.Stats) { nav.go(Screen.Stats) }
        NavItem(if (jobs.busy) "Transcribe  (working…)" else "Transcribe", "Ctrl+5", nav.current is Screen.Transcribe || nav.current is Screen.Transcript || nav.current is Screen.Recording) { nav.go(Screen.Transcribe) }
        NavItem("Recycle bin", "Ctrl+6", nav.current is Screen.Bin) { nav.go(Screen.Bin) }
        Box(Modifier.weight(1f))
        NavItem("Settings", "Ctrl+,", nav.current is Screen.Settings) { nav.go(Screen.Settings) }
    }
}

@Composable
private fun NavItem(label: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1)
        Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}
