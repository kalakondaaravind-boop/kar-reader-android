package com.kar.reader.feature.privacy

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.kar.reader.data.db.KarDatabase
import com.kar.reader.data.files.DocumentStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class StorageFact(val what: String, val where: String, val bytes: Long?, val rebuildable: Boolean)

data class PermissionFact(val name: String, val label: String, val why: String)

data class PrivacyReport(
    val hasNetworkPermission: Boolean,
    val permissions: List<PermissionFact>,
    val storage: List<StorageFact>,
    val bookCount: Int,
)

/**
 * Builds the privacy dashboard from facts, not claims: permissions are read from the installed app's
 * own manifest, and storage sizes are measured on disk.
 */
@Singleton
class PrivacyFacts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DocumentStore,
    private val db: KarDatabase,
) {
    suspend fun report(): PrivacyReport = withContext(Dispatchers.IO) {
        val requested = requestedPermissions()
        val network = requested.any { it == "android.permission.INTERNET" || it == "android.permission.ACCESS_NETWORK_STATE" }
        val books = db.bookDao().getActive()
        PrivacyReport(
            hasNetworkPermission = network,
            permissions = requested.map { describe(it) }.sortedBy { it.label },
            storage = listOf(
                StorageFact("Books and documents", "App storage on this device · ${books.size} files", size(store.libraryDir), false),
                StorageFact("Notes, highlights, bookmarks, progress", "Local database", context.getDatabasePath(KarDatabase.NAME).let { dbFileSize(it) }, false),
                StorageFact("Cover images", "Cache · rebuilt automatically", size(store.coversDir), true),
                StorageFact("Temporary files", "Cache · cleared automatically", size(context.cacheDir), true),
                StorageFact("Backups", "Only where you choose to save them", null, false),
            ),
            bookCount = books.size,
        )
    }

    private fun requestedPermissions(): List<String> {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        return info.requestedPermissions?.toList().orEmpty()
    }

    private fun describe(p: String): PermissionFact = when (p) {
        "android.permission.CAMERA" -> PermissionFact(p, "Camera", "Only while you scan a page")
        "android.permission.USE_BIOMETRIC" -> PermissionFact(p, "Biometrics", "Only to unlock the app or private books")
        "android.permission.ACCESS_NOTIFICATION_POLICY" -> PermissionFact(p, "Do Not Disturb", "Only during focus sessions, if you allow it")
        "android.permission.INTERNET" -> PermissionFact(p, "Network", "Direct Wi-Fi sync between your devices (Sync build)")
        "android.permission.ACCESS_WIFI_STATE" -> PermissionFact(p, "Wi-Fi state", "Finding this device's local address for Wi-Fi sync")
        "android.permission.ACCESS_NETWORK_STATE" -> PermissionFact(p, "Network state", "Requested by a library component")
        else -> PermissionFact(p, p.substringAfterLast('.').replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, "Used by an Android component")
    }

    private fun dbFileSize(dbFile: File): Long =
        listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).sumOf { if (it.isFile) it.length() else 0L }

    private fun size(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { size(it) } ?: 0L
}
