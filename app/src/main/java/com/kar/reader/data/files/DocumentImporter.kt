package com.kar.reader.data.files

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.graphics.pdf.PdfRenderer
import com.kar.reader.core.epub.EpubParseException
import com.kar.reader.core.epub.EpubParser
import com.kar.reader.core.io.FileSignature
import com.kar.reader.core.io.Hashing
import com.kar.reader.core.model.DocumentFormat
import com.kar.reader.core.zip.SafeZipExtractor
import com.kar.reader.core.zip.ZipEntryOutcome
import com.kar.reader.core.zip.ZipExtractionResult
import com.kar.reader.core.zip.ZipLimits
import com.kar.reader.data.db.BookDao
import com.kar.reader.data.db.BookEntity
import com.kar.reader.data.repo.LibraryRepository
import com.kar.reader.data.repo.WidgetRefresher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Optional metadata supplied by the caller (Calibre import, scanner) instead of what the file contains. */
data class MetadataOverride(
    val title: String? = null,
    val author: String? = null,
    val year: String? = null,
    val publisher: String? = null,
    val seriesName: String? = null,
    val seriesIndex: Double? = null,
    val rating: Int? = null,
    val tags: List<String> = emptyList(),
    val coverFile: File? = null,
)

/** A fully copied, validated file waiting for the user's confirmation. */
data class StagedDocument(
    val displayName: String,
    val file: File,
    val format: DocumentFormat,
    val hash: String,
    val sizeBytes: Long,
    val title: String,
    val author: String?,
    val year: String?,
    val publisher: String?,
    val language: String?,
    val pageCount: Int?,
    val duplicateOf: BookEntity?,
    val source: String,
    val override: MetadataOverride? = null,
)

sealed interface ImportProblem {
    val name: String
    val reason: String

    data class Skipped(override val name: String, override val reason: String) : ImportProblem
    data class Rejected(override val name: String, override val reason: String) : ImportProblem
}

data class StagedBatch(
    val dir: File,
    val documents: List<StagedDocument>,
    val problems: List<ImportProblem>,
    val containsArchive: Boolean,
    val archiveName: String?,
    val extractedBytes: Long,
) {
    val newDocuments: List<StagedDocument> get() = documents.filter { it.duplicateOf == null }
}

sealed interface ImportResult {
    val name: String

    data class Imported(override val name: String, val book: BookEntity) : ImportResult
    data class Duplicate(override val name: String, val existing: BookEntity) : ImportResult
    data class Failed(override val name: String, val reason: String) : ImportResult
}

/**
 * Copies user-chosen files into app-managed storage. Files are copied to a private staging folder,
 * identified by content, hashed and validated before any library entry exists, so a failed or
 * interrupted import never leaves a valid-looking but broken book. Originals are never modified.
 */
@Singleton
class DocumentImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DocumentStore,
    private val bookDao: BookDao,
    private val library: LibraryRepository,
    private val covers: CoverGenerator,
    private val widgets: WidgetRefresher,
) {
    companion object {
        const val MAX_DOCUMENT_BYTES = 2L shl 30 // 2 GiB per file
        private const val SPACE_MARGIN_BYTES = 64L shl 20
        val ZIP_LIMITS = ZipLimits()
    }

    /** Copies and checks [uris]. ZIP archives are extracted safely. Nothing is added to the library yet. */
    suspend fun stage(
        uris: List<Uri>,
        onProgress: (String) -> Unit = {},
    ): StagedBatch = withContext(Dispatchers.IO) {
        val dir = store.newStagingDir()
        val docs = mutableListOf<StagedDocument>()
        val problems = mutableListOf<ImportProblem>()
        var archive = false
        var archiveName: String? = null
        var extracted = 0L
        for ((index, uri) in uris.withIndex()) {
            coroutineContext.ensureActive()
            val (name, size) = queryNameAndSize(uri)
            onProgress("Checking ${index + 1} of ${uris.size}: $name")
            if (size != null && size > MAX_DOCUMENT_BYTES) {
                problems += ImportProblem.Rejected(name, "Larger than 2 GB"); continue
            }
            if (size != null && size + SPACE_MARGIN_BYTES > store.usableSpaceBytes()) {
                problems += ImportProblem.Rejected(name, "Not enough free storage"); continue
            }
            val target = File(dir, "in-$index.part")
            try {
                copyUri(uri, target)
            } catch (e: IOException) {
                target.delete()
                problems += ImportProblem.Rejected(name, "Could not read the file"); continue
            } catch (e: SecurityException) {
                target.delete()
                problems += ImportProblem.Rejected(name, "Permission to read the file was refused"); continue
            }
            when (val kind = FileSignature.detect(target)) {
                is FileSignature.Result.Supported -> {
                    val finalFile = File(dir, "doc-$index.${kind.format.extension}")
                    target.renameTo(finalFile)
                    inspect(finalFile, name, kind.format, source = "IMPORT")
                        .onSuccess { docs += it }
                        .onFailure { problems += ImportProblem.Rejected(name, it.message ?: "Unreadable document"); finalFile.delete() }
                }
                FileSignature.Result.ZipArchive -> {
                    archive = true
                    archiveName = name
                    val zipDir = File(dir, "zip-$index").apply { mkdirs() }
                    val result = target.inputStream().use { input ->
                        SafeZipExtractor(ZIP_LIMITS).extract(
                            archive = input,
                            stagingDir = zipDir,
                            accept = { it.endsWith(".pdf", true) || it.endsWith(".epub", true) },
                            isCancelled = { !coroutineContext.isActiveSafe() },
                            onProgress = { count, bytes -> onProgress("Checking $name: $count entries, ${bytes / (1 shl 20)} MB") },
                        )
                    }
                    target.delete()
                    for (outcome in result.outcomes) {
                        when (outcome) {
                            is ZipEntryOutcome.Extracted -> {
                                extracted += outcome.bytes
                                val detected = FileSignature.detect(outcome.file)
                                if (detected is FileSignature.Result.Supported) {
                                    inspect(outcome.file, outcome.entryName.substringAfterLast('/'), detected.format, source = "ZIP")
                                        .onSuccess { docs += it }
                                        .onFailure { problems += ImportProblem.Rejected(outcome.entryName, it.message ?: "Unreadable document") }
                                } else {
                                    problems += ImportProblem.Rejected(outcome.entryName, "Not really a PDF or EPUB")
                                    outcome.file.delete()
                                }
                            }
                            is ZipEntryOutcome.Skipped -> problems += ImportProblem.Skipped(outcome.entryName, "Not a PDF or EPUB")
                            is ZipEntryOutcome.Rejected -> problems += ImportProblem.Rejected(outcome.entryName, "Blocked: ${outcome.reason.lowercase()}")
                        }
                    }
                    when (result) {
                        is ZipExtractionResult.Aborted -> problems += ImportProblem.Rejected(name, "Stopped: ${result.reason}")
                        is ZipExtractionResult.Cancelled -> problems += ImportProblem.Skipped(name, "Cancelled")
                        is ZipExtractionResult.Completed -> Unit
                    }
                }
                is FileSignature.Result.Unsupported -> {
                    target.delete()
                    problems += ImportProblem.Skipped(name, kind.reason)
                }
            }
        }
        // Two copies of the same file in one batch: keep the first.
        val unique = docs.distinctBy { it.hash }
        (docs - unique.toSet()).forEach { problems += ImportProblem.Skipped(it.displayName, "Same file appears twice") }
        StagedBatch(dir, unique, problems, archive, archiveName, extracted)
    }

    /** Stages one local file produced inside the app (scanner) or copied from a Calibre library. */
    suspend fun stageLocal(file: File, displayName: String, source: String, override: MetadataOverride?): Result<StagedDocument> = withContext(Dispatchers.IO) {
        when (val kind = FileSignature.detect(file)) {
            is FileSignature.Result.Supported -> inspect(file, displayName, kind.format, source, override)
            else -> Result.failure(IOException("Not a PDF or EPUB"))
        }
    }

    /** Adds staged documents to the library. Each document is committed on its own, so one failure never affects others. */
    suspend fun commit(documents: List<StagedDocument>, onProgress: (String) -> Unit = {}): List<ImportResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ImportResult>()
        for ((i, doc) in documents.withIndex()) {
            onProgress("Adding ${i + 1} of ${documents.size}: ${doc.title}")
            val existing = bookDao.findByHash(doc.hash)
            if (existing != null) {
                if (existing.deletedAt != null) library.restoreBook(existing.id)
                results += ImportResult.Duplicate(doc.displayName, existing)
                continue
            }
            val id = UUID.randomUUID().toString()
            val storedName = store.storedNameFor(id, doc.format)
            val target = store.libraryFile(storedName)
            val moved = doc.file.renameTo(target) || runCatching { doc.file.copyTo(target, overwrite = false); doc.file.delete(); true }.getOrDefault(false)
            if (!moved || Hashing.sha256(target) != doc.hash) {
                target.delete()
                results += ImportResult.Failed(doc.displayName, "The file changed or could not be stored")
                continue
            }
            val o = doc.override
            val now = System.currentTimeMillis()
            val book = BookEntity(
                id = id,
                title = o?.title ?: doc.title,
                author = o?.author ?: doc.author,
                format = doc.format.name,
                contentHash = doc.hash,
                storedFileName = storedName,
                sourceDisplayName = doc.displayName,
                sizeBytes = doc.sizeBytes,
                pageCount = doc.pageCount,
                language = doc.language,
                year = o?.year ?: doc.year,
                publisher = o?.publisher ?: doc.publisher,
                seriesName = o?.seriesName,
                seriesIndex = o?.seriesIndex,
                rating = o?.rating ?: 0,
                source = doc.source,
                addedAt = now,
            )
            try {
                bookDao.insert(book)
            } catch (e: Exception) {
                target.delete()
                results += ImportResult.Failed(doc.displayName, "Could not save to the library")
                continue
            }
            if (o != null && o.tags.isNotEmpty()) library.setTags(id, o.tags)
            val coverFromCaller = o?.coverFile?.takeIf { it.isFile }
            if (coverFromCaller != null) covers.fromImage(coverFromCaller, store.coverFile(id)) else covers.generate(book, target)
            results += ImportResult.Imported(doc.displayName, book)
        }
        widgets.requestRefresh()
        results
    }

    fun discard(batch: StagedBatch) {
        batch.dir.deleteRecursively()
    }

    private suspend fun inspect(file: File, displayName: String, format: DocumentFormat, source: String, override: MetadataOverride? = null): Result<StagedDocument> =
        withContext(Dispatchers.IO) {
            runCatching {
                val hash = Hashing.sha256(file)
                val baseTitle = displayName.substringBeforeLast('.').ifBlank { "Untitled" }
                var title = baseTitle
                var author: String? = null
                var year: String? = null
                var publisher: String? = null
                var language: String? = null
                var pages: Int? = null
                when (format) {
                    DocumentFormat.PDF -> {
                        try {
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                                PdfRenderer(pfd).use { pages = it.pageCount }
                            }
                        } catch (e: SecurityException) {
                            throw IOException("Password-protected PDFs aren't supported yet")
                        } catch (e: IOException) {
                            throw IOException("This PDF is damaged and can't be opened")
                        }
                    }
                    DocumentFormat.EPUB -> {
                        try {
                            ZipFile(file).use { zip ->
                                val pub = EpubParser.parse(zip)
                                title = pub.title?.takeIf { it.isNotBlank() } ?: baseTitle
                                author = pub.authors.joinToString(", ").ifBlank { null }
                                language = pub.language
                                pages = pub.spine.size
                                year = readDcDate(zip, pub.opfPath)
                                publisher = readDcPublisher(zip, pub.opfPath)
                            }
                        } catch (e: EpubParseException) {
                            throw IOException("This EPUB is damaged: ${e.message}")
                        }
                    }
                }
                StagedDocument(
                    displayName = displayName,
                    file = file,
                    format = format,
                    hash = hash,
                    sizeBytes = file.length(),
                    title = title.take(300),
                    author = author?.take(300),
                    year = year,
                    publisher = publisher?.take(200),
                    language = language,
                    pageCount = pages,
                    duplicateOf = bookDao.findByHash(hash)?.takeIf { it.deletedAt == null },
                    source = source,
                    override = override,
                )
            }
        }

    private fun readDcDate(zip: ZipFile, opfPath: String): String? = readOpfElement(zip, opfPath, "date")?.take(4)?.takeIf { it.all(Char::isDigit) }

    private fun readDcPublisher(zip: ZipFile, opfPath: String): String? = readOpfElement(zip, opfPath, "publisher")

    private fun readOpfElement(zip: ZipFile, opfPath: String, local: String): String? = runCatching {
        val entry = zip.getEntry(opfPath) ?: return null
        val doc = zip.getInputStream(entry).use { EpubParser.parseXml(it) }
        val nodes = doc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", local)
        if (nodes.length > 0) nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() } else null
    }.getOrNull()

    private fun queryNameAndSize(uri: Uri): Pair<String, Long?> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        return name to size
    }

    private suspend fun copyUri(uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No data")
        input.use { inp ->
            target.outputStream().use { out ->
                val buffer = ByteArray(128 * 1024)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = inp.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_DOCUMENT_BYTES) throw IOException("Too large")
                    out.write(buffer, 0, n)
                }
                out.fd.sync()
            }
        }
    }

    private fun kotlin.coroutines.CoroutineContext.isActiveSafe(): Boolean = this[kotlinx.coroutines.Job]?.isActive ?: true
}
