package com.kar.reader.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kar.reader.core.io.FileSignature
import com.kar.reader.core.io.Hashing
import com.kar.reader.core.model.DocumentFormat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Where everything lives: %APPDATA%\KAR READER on Windows (a folder in the home directory elsewhere). */
object Paths {
    val root: File = run {
        val override = System.getenv("KAR_DATA_DIR")           // used by automated checks and portable setups
        val appData = System.getenv("APPDATA")
        File(
            when {
                !override.isNullOrBlank() -> override
                !appData.isNullOrBlank() -> File(appData, "KAR READER").path
                else -> File(System.getProperty("user.home"), ".kar-reader").path
            },
        ).also { it.mkdirs() }
    }
    val books = File(root, "books").also { it.mkdirs() }
    val covers = File(root, "covers").also { it.mkdirs() }
    val recordings = File(root, "recordings").also { it.mkdirs() }
    val transcripts = File(root, "transcripts").also { it.mkdirs() }
    val dataFile = File(root, "data.json")

    /** Models are installed next to the app (Compose resources); a models folder under the data folder also works. */
    val models: File get() {
        val res = System.getProperty("compose.application.resources.dir")
        val installed = res?.let { File(it, "models") }
        return if (installed != null && installed.isDirectory) installed else File(root, "models")
    }
}

val AppJson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

class ImportResult(val added: List<Book>, val skipped: List<String>)

/** The whole library and everything the user made, held in memory and saved to data.json after every change. */
class AppState {
    var data by mutableStateOf(load())
        private set
    var message by mutableStateOf<String?>(null)

    private fun load(): Data {
        val f = Paths.dataFile
        if (!f.isFile) return Data()
        return runCatching { AppJson.decodeFromString<Data>(f.readText()) }.getOrElse {
            // Never overwrite a file we couldn't read: keep it aside and start empty.
            runCatching { f.copyTo(File(Paths.root, "data.unreadable-${System.currentTimeMillis()}.json"), overwrite = true) }
            Data()
        }
    }

    @Synchronized
    fun update(change: (Data) -> Data) {
        data = change(data)
        save()
    }

    @Synchronized
    private fun save() {
        val tmp = File(Paths.root, "data.json.tmp")
        tmp.writeText(AppJson.encodeToString(data))
        Files.move(tmp.toPath(), Paths.dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun toast(text: String) { message = text }

    val liveBooks get() = data.books.filter { it.deletedAt == 0L }
    val binBooks get() = data.books.filter { it.deletedAt != 0L }
    fun book(id: String?) = data.books.firstOrNull { it.id == id }

    fun updateBook(id: String, change: (Book) -> Book) = update { d -> d.copy(books = d.books.map { if (it.id == id) change(it) else it }) }

    /** Copies the files into the library. Duplicates (same content) are skipped, nothing is overwritten. */
    fun importFiles(files: List<File>): ImportResult {
        val added = ArrayList<Book>()
        val skipped = ArrayList<String>()
        for (f in files) {
            try {
                val sig = FileSignature.detect(f)
                val format = (sig as? FileSignature.Result.Supported)?.format
                if (format == null) { skipped += "${f.name}: not a PDF or EPUB file"; continue }
                val hash = Hashing.sha256(f)
                val existing = data.books.firstOrNull { it.hash == hash }
                if (existing != null) {
                    if (existing.deletedAt != 0L) updateBook(existing.id) { it.copy(deletedAt = 0) }
                    skipped += "${f.name}: already in your library"; continue
                }
                val stored = "$hash.${format.extension}"
                val dest = File(Paths.books, stored)
                if (!dest.exists()) f.copyTo(dest)
                val id = UUID.randomUUID().toString()
                val meta = when (format) {
                    DocumentFormat.PDF -> BookReaders.pdfMeta(dest, id, f.nameWithoutExtension)
                    DocumentFormat.EPUB -> BookReaders.epubMeta(dest, id, f.nameWithoutExtension)
                }
                val book = Book(
                    id = id, hash = hash, title = meta.title, authors = meta.authors, year = meta.year, publisher = meta.publisher,
                    format = format.name, file = stored, addedAt = System.currentTimeMillis(), units = meta.units,
                )
                update { it.copy(books = it.books + book) }
                added += book
            } catch (e: Exception) {
                skipped += "${f.name}: ${e.message ?: "couldn't be read"}"
            }
        }
        return ImportResult(added, skipped)
    }

    fun moveToBin(id: String) = updateBook(id) { it.copy(deletedAt = System.currentTimeMillis()) }
    fun restore(id: String) = updateBook(id) { it.copy(deletedAt = 0) }

    /** Permanent removal. Only called after the user confirms; the book's highlights, bookmarks and cards go with it. */
    fun deleteForever(id: String) {
        val b = book(id) ?: return
        update { d ->
            d.copy(
                books = d.books.filter { it.id != id },
                highlights = d.highlights.filter { it.bookId != id },
                bookmarks = d.bookmarks.filter { it.bookId != id },
                cards = d.cards.filter { it.bookId != id },
                sessions = d.sessions.filter { it.bookId != id },
            )
        }
        if (data.books.none { it.file == b.file }) File(Paths.books, b.file).delete()
        File(Paths.covers, "$id.png").delete()
    }

    fun newId(): String = UUID.randomUUID().toString()
}
