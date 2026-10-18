package com.kar.reader.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.KarColors
import com.kar.reader.core.designsystem.KarTheme
import com.kar.reader.core.lock.AppLockManager
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.prefs.AppSettings
import com.kar.reader.ui.calibre.CalibreImportScreen
import com.kar.reader.ui.home.HomeScreen
import com.kar.reader.ui.library.CollectionEditorScreen
import com.kar.reader.ui.library.LibraryScreen
import com.kar.reader.ui.notes.NoteEditorScreen
import com.kar.reader.ui.notes.NotebookScreen
import com.kar.reader.ui.notes.NotesScreen
import com.kar.reader.ui.reader.ReaderHost
import com.kar.reader.ui.review.ReviewScreen
import com.kar.reader.ui.scan.ScanScreen
import com.kar.reader.ui.settings.BackupScreen
import com.kar.reader.ui.settings.ColorMeaningsScreen
import com.kar.reader.ui.settings.ExportScreen
import com.kar.reader.ui.settings.PrivacyScreen
import com.kar.reader.ui.settings.ProfilesScreen
import com.kar.reader.ui.settings.ReadingSettingsScreen
import com.kar.reader.ui.settings.RecycleBinScreen
import com.kar.reader.ui.settings.SettingsScreen
import com.kar.reader.ui.settings.SyncScreen
import com.kar.reader.ui.share.QuoteCardScreen
import com.kar.reader.ui.stats.FocusScreen
import com.kar.reader.ui.stats.StatsScreen
import com.kar.reader.widget.DeepLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DeepLinkRequest(val bookId: String?, val destination: String?, val nonce: Long)

/** Route names. Arguments are ids only; everything else is loaded from the database. */
object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val NOTES = "notes"
    const val REVIEW = "review"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val READER = "reader/{bookId}"
    const val COLLECTION_EDIT = "collection?id={id}"
    const val NOTE = "note/{noteId}"
    const val NOTEBOOK = "notebook/{notebookId}"
    const val QUOTE = "quote/{highlightId}"
    const val FOCUS = "focus"
    const val PRIVACY = "privacy"
    const val PROFILES = "profiles"
    const val BIN = "bin"
    const val SYNC = "sync"
    const val BACKUP = "backup"
    const val EXPORT = "export"
    const val MEANINGS = "meanings"
    const val READING_SETTINGS = "reading-settings"
    const val SCAN = "scan"
    const val CALIBRE = "calibre"

    fun reader(bookId: String) = "reader/$bookId"
    fun collection(id: String?) = if (id == null) "collection" else "collection?id=$id"
    fun note(id: String) = "note/$id"
    fun notebook(id: String) = "notebook/$id"
    fun quote(highlightId: String) = "quote/$highlightId"
}

private data class TopLevel(val route: String, val label: String, val icon: ImageVector)

private val TopLevels = listOf(
    TopLevel(Routes.HOME, "Home", Icons.Filled.Home),
    TopLevel(Routes.LIBRARY, "Library", Icons.AutoMirrored.Filled.LibraryBooks),
    TopLevel(Routes.NOTES, "Notes", Icons.Filled.EditNote),
    TopLevel(Routes.REVIEW, "Review", Icons.Filled.School),
    TopLevel(Routes.STATS, "Stats", Icons.Filled.BarChart),
    TopLevel(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@HiltViewModel
class AppRootViewModel @Inject constructor(prefs: AppPreferences, val lock: AppLockManager) : ViewModel() {
    val settings: StateFlow<AppSettings?> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun AppRoot(activity: FragmentActivity, deepLink: StateFlow<DeepLinkRequest?>, vm: AppRootViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val locked by vm.lock.appLocked.collectAsStateWithLifecycle()
    val s = settings ?: return // first frame: wait for preferences so theme and lock are correct

    KarTheme(themeMode = s.themeMode, eink = s.einkMode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (locked && s.appLockEnabled) {
                LockScreen(onUnlock = { onError -> vm.lock.unlockApp(activity, onError) })
            } else {
                MainNavigation(activity, s, deepLink)
            }
        }
    }
}

@Composable
private fun MainNavigation(activity: FragmentActivity, settings: AppSettings, deepLink: StateFlow<DeepLinkRequest?>) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val isTopLevel = route == null || TopLevels.any { it.route == route }
    val link by deepLink.collectAsStateWithLifecycle()

    LaunchedEffect(link?.nonce) {
        val l = link ?: return@LaunchedEffect
        when {
            l.bookId != null -> nav.navigate(Routes.reader(l.bookId))
            l.destination == DeepLinks.DEST_REVIEW -> nav.navigateTop(Routes.REVIEW)
            l.destination == DeepLinks.DEST_STATS -> nav.navigateTop(Routes.STATS)
            l.destination == DeepLinks.DEST_FOCUS -> nav.navigate(Routes.FOCUS)
        }
    }

    val adaptive = currentWindowAdaptiveInfo()
    val layoutType = if (isTopLevel) NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptive) else NavigationSuiteType.None

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            TopLevels.forEach { item ->
                item(
                    selected = route == item.route,
                    onClick = { nav.navigateTop(item.route) },
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(item.label) },
                )
            }
        },
    ) {
        val eink = settings.einkMode
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            enterTransition = { if (eink) EnterTransition.None else fadeIn() },
            exitTransition = { if (eink) ExitTransition.None else fadeOut() },
            popEnterTransition = { if (eink) EnterTransition.None else fadeIn() },
            popExitTransition = { if (eink) ExitTransition.None else fadeOut() },
        ) {
            val back: () -> Unit = { nav.popBackStack() }
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenBook = { nav.navigate(Routes.reader(it)) },
                    onOpenLibrary = { nav.navigateTop(Routes.LIBRARY) },
                    onOpenReview = { nav.navigateTop(Routes.REVIEW) },
                    onStartFocus = { nav.navigate(Routes.FOCUS) },
                    onOpenStats = { nav.navigateTop(Routes.STATS) },
                )
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    activity = activity,
                    onOpenBook = { nav.navigate(Routes.reader(it)) },
                    onScan = { nav.navigate(Routes.SCAN) },
                    onCalibre = { nav.navigate(Routes.CALIBRE) },
                    onEditCollection = { nav.navigate(Routes.collection(it)) },
                )
            }
            composable(Routes.COLLECTION_EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                CollectionEditorScreen(onBack = back)
            }
            composable(Routes.READER, arguments = listOf(navArgument("bookId") { type = NavType.StringType })) {
                ReaderHost(
                    onBack = back,
                    onQuoteCard = { nav.navigate(Routes.quote(it)) },
                )
            }
            composable(Routes.NOTES) {
                NotesScreen(
                    onOpenNote = { nav.navigate(Routes.note(it)) },
                    onOpenNotebook = { nav.navigate(Routes.notebook(it)) },
                    onOpenBook = { nav.navigate(Routes.reader(it)) },
                    onQuoteCard = { nav.navigate(Routes.quote(it)) },
                    onExport = { nav.navigate(Routes.EXPORT) },
                )
            }
            composable(Routes.NOTE, arguments = listOf(navArgument("noteId") { type = NavType.StringType })) { NoteEditorScreen(onBack = back) }
            composable(Routes.NOTEBOOK, arguments = listOf(navArgument("notebookId") { type = NavType.StringType })) { NotebookScreen(onBack = back) }
            composable(Routes.QUOTE, arguments = listOf(navArgument("highlightId") { type = NavType.StringType })) { QuoteCardScreen(onBack = back) }
            composable(Routes.REVIEW) { ReviewScreen() }
            composable(Routes.STATS) { StatsScreen(onStartFocus = { nav.navigate(Routes.FOCUS) }) }
            composable(Routes.FOCUS) { FocusScreen(onBack = back) }
            composable(Routes.SETTINGS) { SettingsScreen(activity = activity, onNavigate = { nav.navigate(it) }) }
            composable(Routes.PRIVACY) { PrivacyScreen(activity = activity, onBack = back, onNavigate = { nav.navigate(it) }) }
            composable(Routes.PROFILES) { ProfilesScreen(onBack = back) }
            composable(Routes.BIN) { RecycleBinScreen(onBack = back) }
            composable(Routes.SYNC) { SyncScreen(onBack = back) }
            composable(Routes.BACKUP) { BackupScreen(onBack = back) }
            composable(Routes.EXPORT) { ExportScreen(onBack = back) }
            composable(Routes.MEANINGS) { ColorMeaningsScreen(onBack = back) }
            composable(Routes.READING_SETTINGS) { ReadingSettingsScreen(onBack = back) }
            composable(Routes.SCAN) { ScanScreen(onBack = back, onSaved = { id -> nav.popBackStack(); nav.navigate(Routes.reader(id)) }) }
            composable(Routes.CALIBRE) { CalibreImportScreen(onBack = back) }
        }
    }
}

private fun NavHostController.navigateTop(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun LockScreen(onUnlock: ((String) -> Unit) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onUnlock { error = it } }
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF2F5688), Color(0xFF142A47), Color(0xFF0A1424)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(32.dp)) {
            Box(
                Modifier.size(104.dp).background(Brush.linearGradient(listOf(KarColors.GoldLight, KarColors.Gold, KarColors.GoldDeep)), androidx.compose.foundation.shape.RoundedCornerShape(32.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFF2A1A05), modifier = Modifier.size(48.dp)) }
            Spacer(Modifier.height(28.dp))
            Text("KAR READER is locked", color = Color(0xFFFFF6E4), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Spacer(Modifier.height(8.dp))
            Text("Unlock with your fingerprint, face or device PIN.", color = Color(0xFFB8C6DA), textAlign = TextAlign.Center)
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Color(0xFFFFB4A8), textAlign = TextAlign.Center) }
            Spacer(Modifier.height(32.dp))
            GlossyButton("Unlock", onClick = { error = null; onUnlock { error = it } }, kind = GlossyKind.GOLD, icon = Icons.Filled.Lock)
        }
    }
}
