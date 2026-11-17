package com.kar.reader.desktop

import kotlinx.serialization.encodeToString
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/** A backup is one .zip: manifest.json, data.json, and the book, cover, transcript and recording files. */
object Backup {
    private const val FORMAT = "kar-reader-windows-backup"
    private val folders = listOf("books", "covers", "transcripts", "recordings")

    fun export(state: AppState) {
        val chosen = saveFileDialog("Save backup", "KAR-READER-backup-${java.time.LocalDate.now()}.zip") ?: return
        val target = if (chosen.name.endsWith(".zip", true)) chosen else File(chosen.path + ".zip")
        val snapshot = state.data
        state.toast("Backing up…")
        thread(name = "backup", isDaemon = true) {
            try {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { z ->
                    fun bytes(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
                    fun file(name: String, f: File) { if (f.isFile) { z.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
                    bytes("manifest.json", """{"format":"$FORMAT","version":1,"createdAt":${System.currentTimeMillis()}}""".toByteArray())
                    bytes("data.json", AppJson.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
                    snapshot.books.map { it.file }.distinct().forEach { file("books/$it", File(Paths.books, it)) }
                    snapshot.books.forEach { file("covers/${it.id}.png", File(Paths.covers, "${it.id}.png")) }
                    snapshot.transcripts.forEach {
                        file("transcripts/${it.id}.json", File(Paths.transcripts, "${it.id}.json"))
                        file("recordings/${it.audio}", File(Paths.recordings, it.audio))
                    }
                }
                state.toast("Backup saved: ${target.name} (${target.length() / 1_048_576} MB)")
            } catch (e: Exception) {
                state.toast("The backup failed: ${e.message}")
            }
        }
    }

    private fun safeLeaf(entry: String, folder: String): String? {
        if (!entry.startsWith("$folder/")) return null
        val leaf = entry.removePrefix("$folder/")
        return leaf.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it && ':' !in it && ".." !in it }
    }

    fun restore(state: AppState, zipFile: File) {
        state.toast("Restoring…")
        thread(name = "restore", isDaemon = true) {
            try {
                ZipFile(zipFile).use { zip ->
                    val manifest = zip.getEntry("manifest.json")?.let { zip.getInputStream(it).use { s -> String(s.readBytes()) } }.orEmpty()
                    if (FORMAT !in manifest) { state.toast("That isn't a KAR READER backup for Windows."); return@thread }
                    val incoming = AppJson.decodeFromString<Data>(zip.getInputStream(zip.getEntry("data.json") ?: error("data.json is missing")).use { String(it.readBytes(), Charsets.UTF_8) })
                    // Copy files that aren't here yet. Existing files are never replaced.
                    for (e in zip.entries()) {
                        if (e.isDirectory) continue
                        for (folder in folders) {
                            val leaf = safeLeaf(e.name, folder) ?: continue
                            val dest = File(File(Paths.root, folder), leaf)
                            if (!dest.exists()) zip.getInputStream(e).use { input -> dest.outputStream().use { input.copyTo(it) } }
                        }
                    }
                    state.update { local ->
                        val hashToLocal = local.books.associate { it.hash to it.id }
                        val remap = incoming.books.mapNotNull { b -> hashToLocal[b.hash]?.let { b.id to it } }.toMap()
                        fun r(id: String) = remap[id] ?: id
                        fun <T> union(a: List<T>, b: List<T>, id: (T) -> String): List<T> { val have = a.map(id).toSet(); return a + b.filter { id(it) !in have } }
                        local.copy(
                            books = local.books + incoming.books.filter { it.hash !in hashToLocal && local.books.none { l -> l.id == it.id } },
                            highlights = union(local.highlights, incoming.highlights.map { it.copy(bookId = r(it.bookId)) }) { it.id },
                            bookmarks = union(local.bookmarks, incoming.bookmarks.map { it.copy(bookId = r(it.bookId)) }) { it.id },
                            notes = union(local.notes, incoming.notes.map { it.copy(bookId = it.bookId?.let(::r)) }) { it.id },
                            cards = union(local.cards, incoming.cards.map { it.copy(bookId = it.bookId?.let(::r)) }) { it.id },
                            sessions = local.sessions + incoming.sessions.filter { s -> local.sessions.none { it.startedAt == s.startedAt && it.durationMs == s.durationMs } }.map { it.copy(bookId = r(it.bookId)) },
                            transcripts = union(local.transcripts, incoming.transcripts.map { it.copy(bookId = it.bookId?.let(::r)) }) { it.id },
                        )
                    }
                }
                state.toast("Backup restored.")
            } catch (e: Exception) {
                state.toast("Couldn't restore: ${e.message}")
            }
        }
    }
}
