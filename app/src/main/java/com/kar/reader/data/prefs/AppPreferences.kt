package com.kar.reader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LibrarySort { RECENT, TITLE, ADDED }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val libraryGrid: Boolean = true,
    val librarySort: LibrarySort = LibrarySort.RECENT,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val GRID = booleanPreferencesKey("library_grid")
        val SORT = stringPreferencesKey("library_sort")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.SYSTEM,
            libraryGrid = p[Keys.GRID] ?: true,
            librarySort = p[Keys.SORT]?.let { v -> LibrarySort.entries.firstOrNull { it.name == v } } ?: LibrarySort.RECENT,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME] = mode.name }

    suspend fun setLibraryGrid(grid: Boolean) = context.dataStore.edit { it[Keys.GRID] = grid }

    suspend fun setLibrarySort(sort: LibrarySort) = context.dataStore.edit { it[Keys.SORT] = sort.name }
}
