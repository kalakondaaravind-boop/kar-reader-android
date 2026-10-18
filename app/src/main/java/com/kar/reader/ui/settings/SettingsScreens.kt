@file:OptIn(ExperimentalCoroutinesApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.kar.reader.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ChromeReaderMode
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.BuildConfig
import com.kar.reader.core.designsystem.ConfirmDialog
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.GlossyOrb
import com.kar.reader.core.designsystem.KarColors
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.core.designsystem.SectionLabel
import com.kar.reader.core.domain.CitationStyle
import com.kar.reader.core.lock.AppLockManager
import com.kar.reader.core.model.HighlightColor
import com.kar.reader.data.db.ProfileEntity
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.prefs.AppSettings
import com.kar.reader.data.prefs.ThemeMode
import com.kar.reader.data.repo.AnnotationRepository
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.ui.Routes
import com.kar.reader.ui.common.SettingRow
import com.kar.reader.ui.common.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

val ProfileColors = listOf(Color(0xFFE0A33A), Color(0xFF3A66A2), Color(0xFF3E9E62), Color(0xFF8A4FA8), Color(0xFFC0392B), Color(0xFF2E8B8B))

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val prefs: AppPreferences,
    val profiles: ProfileRepository,
    private val annotations: AnnotationRepository,
    val lock: AppLockManager,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val profile: StateFlow<ProfileEntity?> = profiles.current.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val allProfiles: StateFlow<List<ProfileEntity>> = profiles.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val goal: StateFlow<Int> = profiles.currentId.flatMapLatest { prefs.dailyGoalMinutes(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, 30)
    val meanings: StateFlow<Map<HighlightColor, String>> = profiles.currentId.flatMapLatest { annotations.observeMeanings(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HighlightColor.entries.associateWith { it.defaultMeaning })

    fun run(block: suspend AppPreferences.() -> Unit) = viewModelScope.launch { prefs.block() }
    fun setGoal(m: Int) = viewModelScope.launch { prefs.setDailyGoal(profiles.currentIdNow(), m) }
    fun setMeaning(c: HighlightColor, label: String) = viewModelScope.launch { annotations.setMeaning(profiles.currentIdNow(), c, label) }

    fun addProfile(name: String, color: Int, pin: String?) = viewModelScope.launch { profiles.create(name, color, pin) }
    fun rename(p: ProfileEntity, name: String) = viewModelScope.launch { profiles.rename(p, name) }
    fun setPin(p: ProfileEntity, pin: String?) = viewModelScope.launch { profiles.setPin(p, pin) }
    fun delete(p: ProfileEntity) = viewModelScope.launch { profiles.delete(p) }
    fun switchTo(p: ProfileEntity, pin: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = profiles.verifyPin(p, pin)
        if (ok) { profiles.switchTo(p); lock.lockPrivateNow() }
        onResult(ok)
    }
}

@Composable
fun SettingsScreen(activity: FragmentActivity, onNavigate: (String) -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    var lockError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel("KAR READER")
        Text("Settings", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)

        GlassCard(Modifier.fillMaxWidth()) {
            SettingRow("Reading as ${profile?.name ?: ""}", "Family profiles: separate progress, notes and private books", Icons.Filled.Group, onClick = { onNavigate(Routes.PROFILES) })
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Appearance", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m ->
                    FilterChip(selected = settings.themeMode == m, onClick = { vm.run { setThemeMode(m) } }, label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }, enabled = !settings.einkMode)
                }
            }
            SettingRow("Reading, e-ink and page turning", if (settings.einkMode) "E-ink mode is on" else "Goal, page-turn keys, flashcards, citation style", Icons.AutoMirrored.Filled.ChromeReaderMode, onClick = { onNavigate(Routes.READING_SETTINGS) })
            SettingRow("Highlight colour meanings", "Name what each colour means, e.g. yellow = key idea", Icons.Filled.Palette, onClick = { onNavigate(Routes.MEANINGS) })
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Privacy", fontWeight = FontWeight.SemiBold)
            SettingRow("Privacy dashboard", "No internet access · what's stored and where", Icons.Filled.Shield, onClick = { onNavigate(Routes.PRIVACY) })
            SwitchRow("App lock", "Fingerprint, face or device PIN when you open KAR READER", settings.appLockEnabled, icon = Icons.Filled.Lock, onChange = { on ->
                if (on) {
                    vm.lock.authenticate(activity, "Turn on app lock", "Confirm it's you", onSuccess = { vm.run { setAppLock(true) } }, onFailure = { lockError = it })
                } else {
                    vm.lock.authenticate(activity, "Turn off app lock", "Confirm it's you", onSuccess = { vm.run { setAppLock(false) } }, onFailure = { lockError = it })
                }
            })
            lockError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Your data", fontWeight = FontWeight.SemiBold)
            SettingRow("Backup and restore", "A file you keep; optionally includes the books", Icons.Filled.Backup, onClick = { onNavigate(Routes.BACKUP) })
            SettingRow("Sync between your devices", "Through a folder you choose. No cloud, no account", Icons.Filled.Sync, onClick = { onNavigate(Routes.SYNC) })
            SettingRow("Export research notes", "Markdown, BibTeX, Obsidian or CSV", Icons.Filled.Upload, onClick = { onNavigate(Routes.EXPORT) })
            SettingRow("Recycle bin", "Deleted items are kept for 30 days", Icons.Filled.Delete, onClick = { onNavigate(Routes.BIN) })
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("About", fontWeight = FontWeight.SemiBold)
            Text("KAR READER ${BuildConfig.VERSION_NAME}${if (BuildConfig.FLAVOR == "sync") " (Sync build)" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("An offline PDF and EPUB reader. No accounts, ads, tracking or uploads.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ReadingSettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val goal by vm.goal.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        KarTopBar("Reading", onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("E-ink mode", fontWeight = FontWeight.SemiBold)
                SwitchRow("E-ink mode", "Pure black on white, no animations or gradients, for e-ink tablets such as Boox", s.einkMode, onChange = { vm.run { setEinkMode(it) } })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Full refresh every ${s.einkRefreshPages} pages", Modifier.weight(1f))
                    TextButton(onClick = { vm.run { setEinkRefreshPages(s.einkRefreshPages - 1) } }, enabled = s.einkRefreshPages > 1) { Text("−") }
                    TextButton(onClick = { vm.run { setEinkRefreshPages(s.einkRefreshPages + 1) } }) { Text("+") }
                }
                Text(
                    "A brief black-then-white flash clears ghosting. Android has no standard e-ink refresh control, so this works on most e-ink screens but can't use vendor-specific modes.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Page-turn controls", fontWeight = FontWeight.SemiBold)
                SwitchRow("Volume keys turn pages", "Down = next page, up = previous, only while a book is open", s.volumeKeysTurnPages, onChange = { vm.run { setVolumeKeysTurnPages(it) } })
                Text("Keyboard arrows, Page Up/Down and Space always work. Most Bluetooth page-turner remotes send these same keys, so they work too.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Stylus", fontWeight = FontWeight.SemiBold)
                SwitchRow("Palm rejection (stylus-only drawing)", "Only the stylus draws; fingers and palms are ignored. Use two fingers to scroll.", s.stylusOnlyDrawing, onChange = { vm.run { setStylusOnlyDrawing(it) } })
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Study", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily reading goal: $goal min", Modifier.weight(1f))
                    TextButton(onClick = { vm.setGoal(goal - 5) }) { Text("− 5") }
                    TextButton(onClick = { vm.setGoal(goal + 5) }) { Text("+ 5") }
                }
                SwitchRow("Make flashcards from highlights", "New highlights become fill-in-the-gap cards", s.autoFlashcards, onChange = { vm.run { setAutoFlashcards(it) } })
                Text("Citation style", modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CitationStyle.entries.forEach { c -> FilterChip(selected = s.citationStyle == c, onClick = { vm.run { setCitationStyle(c) } }, label = { Text(c.label) }) }
                }
            }
        }
    }
}

@Composable
fun ColorMeaningsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val meanings by vm.meanings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        KarTopBar("Highlight colour meanings", onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Give each colour a meaning. Highlights can be filtered and exported by meaning, and the printable summary groups them this way.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            HighlightColor.entries.forEach { c ->
                var text by remember(meanings[c]) { mutableStateOf(meanings[c] ?: c.defaultMeaning) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlossyOrb(Color(c.argb), Modifier.size(36.dp))
                    Spacer(Modifier.width(14.dp))
                    OutlinedTextField(text, { text = it.take(40) }, singleLine = true, modifier = Modifier.weight(1f), label = { Text(c.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    TextButton(onClick = { vm.setMeaning(c, text) }, enabled = text != meanings[c]) { Text("Save") }
                }
            }
        }
    }
}

@Composable
fun ProfilesScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val profiles by vm.allProfiles.collectAsStateWithLifecycle()
    val current by vm.profile.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<ProfileEntity?>(null) }
    var manage by remember { mutableStateOf<ProfileEntity?>(null) }
    Column(Modifier.fillMaxSize()) {
        KarTopBar("Who's reading?", onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                profiles.forEach { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { if (p.id != current?.id) { if (p.pinHash == null) vm.switchTo(p, "") {} else pinFor = p } }) {
                        GlossyOrb(ProfileColors[p.colorIndex % ProfileColors.size], Modifier.size(110.dp), selected = p.id == current?.id) {
                            Text(p.name.take(1).uppercase(), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 44.sp, color = Color.White)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(listOfNotNull(if (p.id == current?.id) "Reading now" else null, if (p.pinHash != null) "PIN" else null).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { manage = p }) { Text("Manage") }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(enabled = profiles.size < 6) { adding = true }) {
                    GlossyOrb(MaterialTheme.colorScheme.outline, Modifier.size(110.dp)) { Text("+", fontSize = 48.sp, color = Color.White) }
                    Spacer(Modifier.height(10.dp))
                    Text("Add profile", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text("Up to 6", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            GlassCard(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text("Each profile has its own reading progress, highlights, notes, flashcards, statistics and private collection. The library of books is shared. Profiles are stored only on this device.", fontSize = 13.sp)
            }
        }
    }
    if (adding) ProfileEditor(null, onSave = { name, color, pin -> vm.addProfile(name, color, pin) }, onDismiss = { adding = false })
    pinFor?.let { p ->
        var pin by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pinFor = null },
            title = { Text("PIN for ${p.name}") },
            text = {
                Column {
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8); wrong = false }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    if (wrong) Text("Wrong PIN", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = { vm.switchTo(p, pin) { ok -> if (ok) pinFor = null else wrong = true } }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { pinFor = null }) { Text("Cancel") } },
        )
    }
    manage?.let { p ->
        var confirmDelete by remember { mutableStateOf(false) }
        ProfileEditor(p, onSave = { name, _, pin -> vm.rename(p, name); if (pin != null) vm.setPin(p, pin.ifEmpty { null }) }, onDismiss = { manage = null },
            onDelete = if (profiles.size > 1) ({ confirmDelete = true }) else null)
        if (confirmDelete) {
            ConfirmDialog(
                "Remove ${p.name}?", "The profile is removed from this device. Its notes and highlights are kept in the database (not shown) and are included in backups.",
                "Remove", onConfirm = { vm.delete(p); manage = null }, onDismiss = { confirmDelete = false }, destructive = true,
            )
        }
    }
}

@Composable
private fun ProfileEditor(profile: ProfileEntity?, onSave: (String, Int, String?) -> Unit, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null) {
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var color by remember { mutableStateOf(profile?.colorIndex ?: 1) }
    var pin by remember { mutableStateOf("") }
    var changePin by remember { mutableStateOf(profile == null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "New profile" else "Manage ${profile.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, label = { Text("Name") }, singleLine = true)
                if (profile == null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProfileColors.forEachIndexed { i, c -> GlossyOrb(c, Modifier.size(32.dp).clickable { color = i }, selected = color == i) }
                }
                if (changePin) {
                    OutlinedTextField(
                        pin, { pin = it.filter(Char::isDigit).take(8) }, singleLine = true, label = { Text("PIN (optional, 4–8 digits)") },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    if (profile != null) Text("Leave empty to remove the PIN.", fontSize = 12.sp)
                } else {
                    TextButton(onClick = { changePin = true }) { Text(if (profile?.pinHash != null) "Change or remove PIN" else "Add a PIN") }
                }
                if (onDelete != null) { HorizontalDivider(); TextButton(onClick = onDelete) { Text("Remove profile", color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, color, if (changePin) pin else null); onDismiss() }, enabled = name.isNotBlank() && (pin.isEmpty() || pin.length >= 4)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
