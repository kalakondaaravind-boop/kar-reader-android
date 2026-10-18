package com.kar.reader.data.transfer

import android.content.Context
import android.net.Uri
import com.kar.reader.BuildConfig
import com.kar.reader.core.io.FileSignature
import com.kar.reader.core.io.Hashing
import com.kar.reader.core.model.DocumentFormat
import com.kar.reader.data.files.DocumentStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupManifest(
    val app: String = "KAR READER",
    val appVersion: String,
    val formatVersion: Int,
    val createdAt: Long,
    val dataSha256: String,
    val counts: Map<String, Int>,
    val includesBookFiles: Boolean,
    val bookFiles: List<String> = emptyList(),
)

data class BackupPreview(val manifest: BackupManifest, val data: TransferData, val workDir: File)

enum class RestoreMode { MERGE, REPLACE }

/**
 * Versioned local backups: a ZIP with manifest.json (counts + SHA-256 of the data), data.json, and
 * optionally the book files. Restores validate everything before touching the database, and the merge
 * runs in a single transaction. Backups never leave the device unless the user moves the file.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: TransferEngine,
    private val store: DocumentStore,
    private val json: Json,
) {
    companion object {
        private const val MAX_DATA_BYTES = 256L shl 20
        private const val MAX_BOOK_BYTES = 2L shl 30
        private val BOOK_ENTRY = Regex("^books/([0-9a-f]{64})\\.(pdf|epub)$")
    }

    suspend fun createBackup(destination: Uri, includeBookFiles: Boolean, onProgress: (String) -> Unit = {}): BackupManifest = withContext(Dispatchers.IO) {
        onProgress("Collecting your data")
        val data = engine.collect(profileId = null, deviceId = null, deviceName = null)
        val dataBytes = json.encodeToString(TransferData.serializer(), data).toByteArray(Charsets.UTF_8)
        val activeBooks = data.books.filter { it.deletedAt == null }
        val bookFiles = if (includeBookFiles) activeBooks.filter { store.libraryFile(it.storedFileName).isFile } else emptyList()
        val manifest = BackupManifest(
            appVersion = BuildConfig.VERSION_NAME,
            formatVersion = TransferData.FORMAT_VERSION,
            createdAt = data.createdAt,
            dataSha256 = Hashing.sha256(dataBytes.inputStream()),
            counts = data.counts(),
            includesBookFiles = includeBookFiles,
            bookFiles = bookFiles.map { "books/${it.contentHash}.${it.format.lowercase()}" },
        )
        val tmp = File(store.exportDir, "backup-${System.nanoTime()}.zip")
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("data.json"))
                zip.write(dataBytes)
                zip.closeEntry()
                bookFiles.forEachIndexed { i, book ->
                    onProgress("Adding book ${i + 1} of ${bookFiles.size}")
                    zip.putNextEntry(ZipEntry("books/${book.contentHash}.${book.format.lowercase()}"))
                    store.libraryFile(book.storedFileName).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            // Verify the archive reads back before giving it to the user.
            readArchive(tmp, extractBooksTo = null)
            val out = context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("Can't write to that location")
            out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
        } finally {
            tmp.delete()
        }
        manifest
    }

    /** Copies the chosen backup into private storage, then validates its structure, version and checksum. */
    suspend fun preview(source: Uri): BackupPreview = withContext(Dispatchers.IO) {
        val workDir = store.newStagingDir()
        val local = File(workDir, "backup.zip")
        val input = context.contentResolver.openInputStream(source) ?: throw IOException("Can't read that file")
        input.use { i -> local.outputStream().use { i.copyTo(it) } }
        try {
            val (manifest, data) = readArchive(local, extractBooksTo = File(workDir, "books").apply { mkdirs() })
            BackupPreview(manifest, data, workDir)
        } catch (e: Exception) {
            workDir.deleteRecursively()
            throw e
        }
    }

    suspend fun restore(preview: BackupPreview, mode: RestoreMode): MergeReport = withContext(Dispatchers.IO) {
        try {
            val booksDir = File(preview.workDir, "books")
            engine.merge(
                data = preview.data,
                profileOverride = null,
                resolveMissingBook = { book ->
                    val candidate = File(booksDir, "${book.contentHash}.${book.format.lowercase()}")
                    val format = DocumentFormat.fromNameOrNull(book.format)
                    if (format == null || !candidate.isFile || Hashing.sha256(candidate) != book.contentHash) {
                        null
                    } else {
                        val stored = store.storedNameFor(book.id, format)
                        val target = store.libraryFile(stored)
                        if (!target.exists() && candidate.renameTo(target)) stored else null
                    }
                },
                clearUserDataFirst = mode == RestoreMode.REPLACE,
                historyOrigin = "RESTORE",
            )
        } finally {
            preview.workDir.deleteRecursively()
        }
    }

    fun discard(preview: BackupPreview) { preview.workDir.deleteRecursively() }

    /** Reads and validates a backup ZIP. Unknown or unsafe entries are rejected. */
    private fun readArchive(file: File, extractBooksTo: File?): Pair<BackupManifest, TransferData> {
        var manifestBytes: ByteArray? = null
        var dataBytes: ByteArray? = null
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == "manifest.json" -> manifestBytes = readCapped(zip, 1L shl 20)
                    entry.name == "data.json" -> dataBytes = readCapped(zip, MAX_DATA_BYTES)
                    BOOK_ENTRY.matches(entry.name) -> {
                        if (extractBooksTo != null) {
                            val out = File(extractBooksTo, entry.name.removePrefix("books/"))
                            var total = 0L
                            out.outputStream().use { o ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zip.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_BOOK_BYTES) throw IOException("A book in the backup is too large")
                                    o.write(buf, 0, n)
                                }
                            }
                            if (FileSignature.detect(out) !is FileSignature.Result.Supported) out.delete()
                        }
                    }
                    else -> throw IOException("Not a KAR READER backup (unexpected entry)")
                }
            }
        }
        val m = manifestBytes ?: throw IOException("Not a KAR READER backup (no manifest)")
        val d = dataBytes ?: throw IOException("Backup is incomplete (no data)")
        val manifest = runCatching { json.decodeFromString(BackupManifest.serializer(), m.toString(Charsets.UTF_8)) }
            .getOrElse { throw IOException("Backup manifest is damaged") }
        if (manifest.app != "KAR READER") throw IOException("Not a KAR READER backup")
        if (manifest.formatVersion > TransferData.FORMAT_VERSION) throw IOException("This backup was made by a newer version of KAR READER")
        if (Hashing.sha256(d.inputStream()) != manifest.dataSha256) throw IOException("Backup is damaged (checksum mismatch)")
        val data = runCatching { json.decodeFromString(TransferData.serializer(), d.toString(Charsets.UTF_8)) }
            .getOrElse { throw IOException("Backup data is damaged") }
        return manifest to data
    }

    private fun readCapped(zip: ZipInputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("Backup entry is too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
