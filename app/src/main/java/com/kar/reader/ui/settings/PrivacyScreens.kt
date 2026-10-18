@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kar.reader.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.ConfirmDialog
import com.kar.reader.core.designsystem.EmptyState
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.HeroPanel
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.core.designsystem.LocalKarStyle
import com.kar.reader.core.lock.AppLockManager
import com.kar.reader.data.db.KarDatabase
import com.kar.reader.data.files.DocumentStore
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.prefs.AppSettings
import com.kar.reader.data.repo.BinItem
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.data.repo.TrashRepository
import com.kar.reader.feature.privacy.PrivacyFacts
import com.kar.reader.feature.privacy.PrivacyReport
import com.kar.reader.ui.Routes
import com.kar.reader.ui.common.Format
import com.kar.reader.ui.common.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val facts: PrivacyFacts,
    private val prefs: AppPreferences,
    private val db: KarDatabase,
    private val store: DocumentStore,
    private val profiles: ProfileRepository,
    val lock: AppLockManager,
) : ViewModel() {
    val report = MutableStateFlow<PrivacyReport?>(null)
    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun refresh() = viewModelScope.launch { report.value = facts.report() }

    fun run(block: suspend AppPreferences.() -> Unit) = viewModelScope.launch { prefs.block() }

    /** Erases every book, note, highlight, setting and profile on this device, then restarts the app. */
    fun deleteEverything() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            db.clearAllTables()
            store.libraryDir.deleteRecursively()
            store.coversDir.deleteRecursively()
            store.cleanInterruptedStaging()
            store.exportDir.deleteRecursively()
            prefs.clearAll()
            profiles.ensureDefaultProfile()
        }
        val restart = context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (restart != null) context.startActivity(restart)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrivacyScreen(activity: FragmentActivity, onBack: () -> Unit, onNavigate: (String) -> Unit, vm: PrivacyViewModel = hiltViewModel()) {
    val report by vm.report.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirmWipe by remember { mutableStateOf(false) }
    var lockError by remember { mutableStateOf<String?>(null) }
    val glossy = LocalKarStyle.current.glossy
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        KarTopBar("Privacy", onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val r = report
            if (r != null) {
                val offline = !r.hasNetworkPermission
                HeroPanel(Modifier.fillMaxWidth(), colors = if (offline) listOf(Color(0xFF1F4A3A), Color(0xFF14352A), Color(0xFF0C241C)) else listOf(Color(0xFF5A3E0C), Color(0xFF3B2705), Color(0xFF241703))) {
                    Icon(if (offline) Icons.Filled.Shield else Icons.Filled.Warning, null, tint = if (glossy) Color(0xFF9BE3B0) else Color.Black, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (offline) "VERIFIED FROM THIS APP'S PERMISSIONS" else "SYNC BUILD", style = MaterialTheme.typography.labelSmall, color = if (glossy) Color(0xFF9BE3B0) else Color.Black)
                        Text(
                            if (offline) "This app has no internet access" else "Network access is used only for Wi-Fi sync",
                            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = if (glossy) Color.White else Color.Black,
                        )
                        Text(
                            if (offline) "KAR READER doesn't request network permission, so Android blocks every connection it could make. Nothing you read or write leaves this device unless you export or share it yourself."
                            else "This is the KAR READER Sync build. It can connect to your own devices on the local network when you start a Wi-Fi sync. It has no accounts, ads, tracking or uploads.",
                            color = if (glossy) Color(0xFFB9D6C4) else Color.Black, fontSize = 14.sp,
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    GlassCard(Modifier.weight(1.4f).widthIn(min = 300.dp)) {
                        Text("What's stored, and where", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Spacer(Modifier.height(8.dp))
                        r.storage.forEach { s ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.what, fontWeight = FontWeight.SemiBold)
                                    Text(s.where, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(Format.bytes(s.bytes), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlossyButton("Export all my data", onClick = { onNavigate(Routes.BACKUP) }, compact = true)
                            GlossyButton("Delete all data…", onClick = { confirmWipe = true }, kind = GlossyKind.DANGER, compact = true)
                        }
                    }
                    GlassCard(Modifier.weight(1f).widthIn(min = 280.dp)) {
                        Text("App lock", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        SwitchRow("Lock app", "Fingerprint, face or device PIN", settings.appLockEnabled, onChange = { on ->
                            vm.lock.authenticate(activity, if (on) "Turn on app lock" else "Turn off app lock", "Confirm it's you",
                                onSuccess = { vm.run { setAppLock(on) } }, onFailure = { lockError = it })
                        })
                        SwitchRow("Lock immediately on leaving", "Otherwise after 1 minute away", settings.lockImmediately, onChange = { vm.run { setLockImmediately(it) } })
                        SwitchRow("Hide private books from Recent", "Also hidden from home-screen widgets", settings.hidePrivateFromRecent, onChange = { vm.run { setHidePrivateFromRecent(it) } })
                        lockError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                        Spacer(Modifier.height(10.dp))
                        Text("PERMISSIONS THIS APP REQUESTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        r.permissions.forEach { p -> Text("${p.label} — ${p.why}", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Data on this device is protected by Android's device encryption. App lock hides KAR READER's screens and recent-apps preview; KAR READER doesn't add a separate encryption layer.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().height(200.dp))
            }
        }
    }
    if (confirmWipe) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This permanently removes every book copy, note, highlight, flashcard, profile and setting from KAR READER on this device. It can't be undone. Your original files outside the app and any backups you saved are not touched.")
                    Text("Type DELETE to confirm.", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(typed, { typed = it }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { confirmWipe = false; vm.deleteEverything() }, enabled = typed == "DELETE") { Text("Delete everything", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

@HiltViewModel
class RecycleBinViewModel @Inject constructor(profiles: ProfileRepository, private val trash: TrashRepository) : ViewModel() {
    val items: StateFlow<List<BinItem>> = profiles.currentId.flatMapLatest { trash.observeBin(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    init { viewModelScope.launch { trash.purgeExpired() } }
    fun restore(item: BinItem) = viewModelScope.launch { trash.restore(item) }
    fun empty() = viewModelScope.launch { trash.emptyNow() }
}

@Composable
fun RecycleBinScreen(onBack: () -> Unit, vm: RecycleBinViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    var confirmEmpty by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    Column(Modifier.fillMaxSize()) {
        KarTopBar(
            "Recycle bin", subtitle = "Items stay here for 30 days, then are removed for good", onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack,
            actions = { if (items.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty bin") } },
        )
        if (items.isEmpty()) {
            EmptyState("The recycle bin is empty", "Deleted books, notes, highlights, bookmarks, ink and words wait here for 30 days so you can restore them.")
        } else {
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.kind.name + it.id }) { item ->
                    val days = item.daysLeft(now)
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${item.kind.label.uppercase()} · deleted ${Format.relative(item.deletedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(item.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                            Text(
                                if (days == 1) "1 day left" else "$days days left", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                color = when { days <= 5 -> MaterialTheme.colorScheme.error; days <= 15 -> MaterialTheme.colorScheme.secondary; else -> MaterialTheme.colorScheme.tertiary },
                                modifier = Modifier.padding(horizontal = 10.dp),
                            )
                            GlossyButton("Restore", onClick = { vm.restore(item) }, compact = true)
                        }
                    }
                }
            }
        }
    }
    if (confirmEmpty) {
        ConfirmDialog(
            "Empty the recycle bin?", "Everything in the bin is removed permanently, including the app's copies of deleted books. This can't be undone.",
            "Empty bin", onConfirm = vm::empty, onDismiss = { confirmEmpty = false }, destructive = true,
        )
    }
}
