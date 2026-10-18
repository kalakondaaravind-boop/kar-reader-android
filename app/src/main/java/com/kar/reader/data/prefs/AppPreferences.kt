package com.kar.reader.data.prefs

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kar.reader.core.domain.CitationStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LibrarySort(val label: String) { RECENT("Recently opened"), TITLE("Title"), ADDED("Date added"), AUTHOR("Author") }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val einkMode: Boolean = false,
    val einkRefreshPages: Int = 10,
    val volumeKeysTurnPages: Boolean = true,
    val libraryGrid: Boolean = true,
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val appLockEnabled: Boolean = false,
    val lockImmediately: Boolean = false,
    val hidePrivateFromRecent: Boolean = true,
    val autoFlashcards: Boolean = true,
    val stylusOnlyDrawing: Boolean = false,
    val citationStyle: CitationStyle = CitationStyle.APA,
    val syncFolderUri: String? = null,
    val lastSyncAt: Long = 0,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val EINK = booleanPreferencesKey("eink_mode")
        val EINK_REFRESH = intPreferencesKey("eink_refresh_pages")
        val VOLUME_KEYS = booleanPreferencesKey("volume_keys")
        val GRID = booleanPreferencesKey("library_grid")
        val SORT = stringPreferencesKey("library_sort")
        val LOCK = booleanPreferencesKey("app_lock")
        val LOCK_NOW = booleanPreferencesKey("lock_immediately")
        val HIDE_PRIVATE = booleanPreferencesKey("hide_private_recent")
        val AUTO_CARDS = booleanPreferencesKey("auto_flashcards")
        val STYLUS_ONLY = booleanPreferencesKey("stylus_only")
        val CITATION = stringPreferencesKey("citation_style")
        val SYNC_URI = stringPreferencesKey("sync_folder_uri")
        val LAST_SYNC = longPreferencesKey("last_sync_at")
        val PROFILE = stringPreferencesKey("current_profile")
        val DEVICE_ID = stringPreferencesKey("device_id")
        fun goal(profileId: String) = intPreferencesKey("goal_$profileId")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.SYSTEM,
            einkMode = p[Keys.EINK] ?: false,
            einkRefreshPages = p[Keys.EINK_REFRESH] ?: 10,
            volumeKeysTurnPages = p[Keys.VOLUME_KEYS] ?: true,
            libraryGrid = p[Keys.GRID] ?: true,
            librarySort = p[Keys.SORT]?.let { v -> LibrarySort.entries.firstOrNull { it.name == v } } ?: LibrarySort.RECENT,
            appLockEnabled = p[Keys.LOCK] ?: false,
            lockImmediately = p[Keys.LOCK_NOW] ?: false,
            hidePrivateFromRecent = p[Keys.HIDE_PRIVATE] ?: true,
            autoFlashcards = p[Keys.AUTO_CARDS] ?: true,
            stylusOnlyDrawing = p[Keys.STYLUS_ONLY] ?: false,
            citationStyle = p[Keys.CITATION]?.let { v -> CitationStyle.entries.firstOrNull { it.name == v } } ?: CitationStyle.APA,
            syncFolderUri = p[Keys.SYNC_URI],
            lastSyncAt = p[Keys.LAST_SYNC] ?: 0,
        )
    }.distinctUntilChanged()

    val currentProfileId: Flow<String?> = context.dataStore.data.map { it[Keys.PROFILE] }.distinctUntilChanged()

    fun dailyGoalMinutes(profileId: String): Flow<Int> = context.dataStore.data.map { it[Keys.goal(profileId)] ?: 30 }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setEinkMode(on: Boolean) = edit { it[Keys.EINK] = on }
    suspend fun setEinkRefreshPages(pages: Int) = edit { it[Keys.EINK_REFRESH] = pages.coerceIn(1, 50) }
    suspend fun setVolumeKeysTurnPages(on: Boolean) = edit { it[Keys.VOLUME_KEYS] = on }
    suspend fun setLibraryGrid(grid: Boolean) = edit { it[Keys.GRID] = grid }
    suspend fun setLibrarySort(sort: LibrarySort) = edit { it[Keys.SORT] = sort.name }
    suspend fun setAppLock(on: Boolean) = edit { it[Keys.LOCK] = on }
    suspend fun setLockImmediately(on: Boolean) = edit { it[Keys.LOCK_NOW] = on }
    suspend fun setHidePrivateFromRecent(on: Boolean) = edit { it[Keys.HIDE_PRIVATE] = on }
    suspend fun setAutoFlashcards(on: Boolean) = edit { it[Keys.AUTO_CARDS] = on }
    suspend fun setStylusOnlyDrawing(on: Boolean) = edit { it[Keys.STYLUS_ONLY] = on }
    suspend fun setCitationStyle(style: CitationStyle) = edit { it[Keys.CITATION] = style.name }
    suspend fun setSyncFolder(uri: String?) = edit { if (uri == null) it.remove(Keys.SYNC_URI) else it[Keys.SYNC_URI] = uri }
    suspend fun setLastSyncAt(time: Long) = edit { it[Keys.LAST_SYNC] = time }
    suspend fun setCurrentProfile(id: String) = edit { it[Keys.PROFILE] = id }
    suspend fun setDailyGoal(profileId: String, minutes: Int) = edit { it[Keys.goal(profileId)] = minutes.coerceIn(5, 600) }

    /** Random per-install identifier used only to name this device's sync file. Never leaves the device except in that file. */
    suspend fun deviceId(): String {
        val existing = context.dataStore.data.first()[Keys.DEVICE_ID]
        if (existing != null) return existing
        val id = UUID.randomUUID().toString().take(8)
        edit { it[Keys.DEVICE_ID] = id }
        return id
    }

    fun deviceName(): String = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL).joinToString(" ").ifBlank { "Android device" }

    suspend fun clearAll() = edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
