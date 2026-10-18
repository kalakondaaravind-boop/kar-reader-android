package com.kar.reader.feature.calibre

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kar.reader.data.files.DocumentImporter
import com.kar.reader.data.files.DocumentStore
import com.kar.reader.data.files.ImportResult
import com.kar.reader.data.files.MetadataOverride
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class CalibreBook(
    val id: Long,
    val title: String,
    val authors: String,
    val series: String?,
    val seriesIndex: Double?,
    val rating: Int,
    val tags: List<String>,
    val publisher: String?,
    val year: String?,
    val path: String,
    /** Format to import ("EPUB" preferred over "PDF") and its file name without extension, or null if neither exists. */
    val format: String?,
    val fileName: String?,
    val hasCover: Boolean,
)

data class CalibreLibrary(val books: List<CalibreBook>, val authorCount: Int) {
    val importable: List<CalibreBook> get() = books.filter { it.format != null }
    val skipped: List<CalibreBook> get() = books.filter { it.format == null }
}

data class CalibreOptions(val keepTags: Boolean = true, val keepSeries: Boolean = true, val keepRatings: Boolean = true, val importCovers: Boolean = true)

/**
 * Imports a Calibre library folder chosen by the user. Calibre's metadata.db is copied to private storage
 * and opened read-only; the user's Calibre folder is only read, never changed.
 */
@Singleton
class CalibreImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DocumentStore,
    private val importer: DocumentImporter,
) {

    suspend fun read(tree: Uri): CalibreLibrary = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Can't open that folder")
        val metadata = root.findFile("metadata.db") ?: throw IOException("This folder has no metadata.db. Choose the Calibre Library folder itself.")
        val copy = File(store.newStagingDir(), "metadata.db")
        context.contentResolver.openInputStream(metadata.uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("Can't read metadata.db")
        try {
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> readBooks(db) }
        } catch (e: android.database.sqlite.SQLiteException) {
            throw IOException("metadata.db couldn't be read. Is this a Calibre library?")
        } finally {
            copy.parentFile?.deleteRecursively()
        }
    }

    private fun readBooks(db: SQLiteDatabase): CalibreLibrary {
        val sql = """
            SELECT b.id, b.title, b.path, b.series_index, b.pubdate, b.has_cover,
              (SELECT GROUP_CONCAT(a.name, ' & ') FROM authors a JOIN books_authors_link l ON a.id = l.author WHERE l.book = b.id) AS authors,
              (SELECT s.name FROM series s JOIN books_series_link l ON s.id = l.series WHERE l.book = b.id) AS series,
              (SELECT r.rating FROM ratings r JOIN books_ratings_link l ON r.id = l.rating WHERE l.book = b.id) AS rating,
              (SELECT GROUP_CONCAT(t.name, '|') FROM tags t JOIN books_tags_link l ON t.id = l.tag WHERE l.book = b.id) AS tags,
              (SELECT p.name FROM publishers p JOIN books_publishers_link l ON p.id = l.publisher WHERE l.book = b.id) AS publisher,
              (SELECT GROUP_CONCAT(d.format || ':' || d.name, '|') FROM data d WHERE d.book = b.id) AS formats
            FROM books b ORDER BY b.sort
        """.trimIndent()
        val books = mutableListOf<CalibreBook>()
        val authors = HashSet<String>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val formats = c.getString(11)?.split('|')?.mapNotNull { f ->
                    val fmt = f.substringBefore(':').uppercase()
                    val name = f.substringAfter(':', "")
                    if (name.isEmpty()) null else fmt to name
                }.orEmpty()
                val chosen = formats.firstOrNull { it.first == "EPUB" } ?: formats.firstOrNull { it.first == "PDF" }
                val authorText = c.getString(6).orEmpty()
                authorText.split(" & ").filter { it.isNotBlank() }.forEach { authors += it }
                val pub = c.getString(4).orEmpty()
                books += CalibreBook(
                    id = c.getLong(0),
                    title = c.getString(1).orEmpty(),
                    path = c.getString(2).orEmpty(),
                    seriesIndex = if (c.isNull(3)) null else c.getDouble(3),
                    year = pub.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) && it != "0101" },
                    hasCover = c.getInt(5) != 0,
                    authors = authorText,
                    series = c.getString(7),
                    rating = if (c.isNull(8)) 0 else (c.getInt(8) / 2).coerceIn(0, 5),
                    tags = c.getString(9)?.split('|')?.filter { it.isNotBlank() }.orEmpty(),
                    publisher = c.getString(10),
                    format = chosen?.first,
                    fileName = chosen?.second,
                )
            }
        }
        return CalibreLibrary(books, authors.size)
    }

    suspend fun import(
        tree: Uri,
        books: List<CalibreBook>,
        options: CalibreOptions,
        onProgress: (Int, Int, String) -> Unit,
    ): List<ImportResult> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Can't open that folder")
        val results = mutableListOf<ImportResult>()
        val work = store.newStagingDir()
        try {
            books.forEachIndexed { i, book ->
                coroutineContext.ensureActive()
                onProgress(i + 1, books.size, book.title)
                val format = book.format ?: return@forEachIndexed
                val dir = book.path.split('/').filter { it.isNotEmpty() && it != ".." }.fold(root as DocumentFile?) { d, seg -> d?.findFile(seg) }
                val fileDoc = dir?.findFile("${book.fileName}.${format.lowercase()}")
                if (fileDoc == null) {
                    results += ImportResult.Failed(book.title, "File not found in the Calibre folder"); return@forEachIndexed
                }
                val local = File(work, "calibre-${book.id}.${format.lowercase()}")
                runCatching { context.contentResolver.openInputStream(fileDoc.uri)?.use { inp -> local.outputStream().use { inp.copyTo(it) } } }
                if (!local.isFile) {
                    results += ImportResult.Failed(book.title, "Couldn't read the file"); return@forEachIndexed
                }
                val cover = if (options.importCovers && book.hasCover) {
                    dir?.findFile("cover.jpg")?.let { c ->
                        val f = File(work, "cover-${book.id}.jpg")
                        runCatching { context.contentResolver.openInputStream(c.uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } } }
                        f.takeIf { it.isFile }
                    }
                } else null
                val override = MetadataOverride(
                    title = book.title.ifBlank { null },
                    author = book.authors.replace(" & ", ", ").ifBlank { null },
                    year = book.year,
                    publisher = book.publisher,
                    seriesName = if (options.keepSeries) book.series else null,
                    seriesIndex = if (options.keepSeries) book.seriesIndex else null,
                    rating = if (options.keepRatings) book.rating else null,
                    tags = if (options.keepTags) book.tags else emptyList(),
                    coverFile = cover,
                )
                importer.stageLocal(local, "${book.title}.${format.lowercase()}", "CALIBRE", override)
                    .onSuccess { staged -> results += importer.commit(listOf(staged)) }
                    .onFailure { results += ImportResult.Failed(book.title, it.message ?: "Unreadable") }
                local.delete()
                cover?.delete()
            }
        } finally {
            work.deleteRecursively()
        }
        results
    }
}
