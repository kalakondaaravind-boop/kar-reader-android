package com.kar.reader.data.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.repo.ProfileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class SyncReport(val devicesSeen: List<String>, val merge: MergeReport?, val wroteOwnFile: Boolean)

/**
 * Folder sync without a cloud: each device writes "kar-sync-<deviceId>.json" (its current profile's data)
 * into a folder the user picks, and merges the files other devices left there. Any tool can carry the
 * folder between devices (Syncthing, a USB drive, a memory card). Book files are matched by content
 * hash and are not copied; books missing on one device are reported, not invented.
 */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: TransferEngine,
    private val profiles: ProfileRepository,
    private val prefs: AppPreferences,
    private val json: Json,
) {
    companion object {
        private const val PREFIX = "kar-sync-"
        private const val MAX_SNAPSHOT_BYTES = 128L shl 20
    }

    fun takePersistablePermission(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    suspend fun syncNow(): SyncReport = withContext(Dispatchers.IO) {
        val uri = prefs.settings.first().syncFolderUri?.let(Uri::parse) ?: throw IOException("Choose a sync folder first")
        val folder = DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canRead() && it.canWrite() }
            ?: throw IOException("KAR READER can no longer open the sync folder. Choose it again.")
        val deviceId = prefs.deviceId()
        val profileId = profiles.currentIdNow()

        // 1. Merge what other devices wrote.
        val seen = mutableListOf<String>()
        var report: MergeReport? = null
        folder.listFiles().filter { f -> f.isFile && f.name?.startsWith(PREFIX) == true && f.name?.endsWith(".json") == true && f.name != fileName(deviceId) }
            .forEach { f ->
                if (f.length() > MAX_SNAPSHOT_BYTES) return@forEach
                val text = context.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@forEach
                val snapshot = runCatching { json.decodeFromString(TransferData.serializer(), text) }.getOrNull() ?: return@forEach
                seen += snapshot.deviceName ?: f.name.orEmpty()
                val r = engine.merge(snapshot, profileOverride = profileId, resolveMissingBook = { null }, clearUserDataFirst = false, historyOrigin = "SYNC")
                report = report?.let { MergeReport(it.added + r.added, it.updated + r.updated, it.keptAsHistory + r.keptAsHistory, (it.missingBooks + r.missingBooks).distinct()) } ?: r
            }

        // 2. Write this device's snapshot (after merging, so it carries everything known here).
        val mine = engine.collect(profileId, deviceId, prefs.deviceName())
        val bytes = json.encodeToString(TransferData.serializer(), mine).toByteArray(Charsets.UTF_8)
        val existing = folder.findFile(fileName(deviceId))
        val target = existing ?: folder.createFile("application/json", fileName(deviceId)) ?: throw IOException("Can't write to the sync folder")
        val out = context.contentResolver.openOutputStream(target.uri, "wt") ?: throw IOException("Can't write to the sync folder")
        out.use { it.write(bytes) }
        prefs.setLastSyncAt(System.currentTimeMillis())
        SyncReport(seen, report, wroteOwnFile = true)
    }

    /** Builds this device's snapshot for Wi-Fi sync. */
    suspend fun snapshotBytes(): ByteArray = withContext(Dispatchers.IO) {
        val data = engine.collect(profiles.currentIdNow(), prefs.deviceId(), prefs.deviceName())
        json.encodeToString(TransferData.serializer(), data).toByteArray(Charsets.UTF_8)
    }

    /** Merges a snapshot received over Wi-Fi. */
    suspend fun mergeSnapshot(bytes: ByteArray): MergeReport = withContext(Dispatchers.IO) {
        val snapshot = json.decodeFromString(TransferData.serializer(), bytes.toString(Charsets.UTF_8))
        engine.merge(snapshot, profiles.currentIdNow(), { null }, clearUserDataFirst = false, historyOrigin = "SYNC")
    }

    private fun fileName(deviceId: String) = "$PREFIX$deviceId.json"
}
