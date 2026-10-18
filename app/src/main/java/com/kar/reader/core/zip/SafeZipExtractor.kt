package com.kar.reader.core.zip

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Resource limits for archive extraction. Defaults are deliberately conservative for a
 * personal library on a tablet and are documented in docs/SECURITY_LIMITS.md.
 */
data class ZipLimits(
    val maxEntries: Int = 2_000,
    val maxEntryBytes: Long = 1L shl 30,        // 1 GiB per extracted file
    val maxTotalBytes: Long = 4L shl 30,        // 4 GiB per archive
    val maxCompressionRatio: Long = 200,        // extracted / compressed, per entry
    val ratioCheckThresholdBytes: Long = 1L shl 20, // only apply ratio check above 1 MiB
)

sealed interface ZipEntryOutcome {
    val entryName: String

    data class Extracted(override val entryName: String, val file: File, val bytes: Long) : ZipEntryOutcome
    data class Skipped(override val entryName: String, val reason: String) : ZipEntryOutcome
    data class Rejected(override val entryName: String, val reason: String) : ZipEntryOutcome
}

sealed interface ZipExtractionResult {
    val outcomes: List<ZipEntryOutcome>

    data class Completed(override val outcomes: List<ZipEntryOutcome>) : ZipExtractionResult

    /** Extraction stopped. Files listed as Extracted before the failure are complete and intact. */
    data class Aborted(override val outcomes: List<ZipEntryOutcome>, val reason: String) : ZipExtractionResult

    data class Cancelled(override val outcomes: List<ZipEntryOutcome>) : ZipExtractionResult
}

/**
 * Streams entries from an untrusted ZIP into [stagingDir], enforcing path safety and size limits
 * using the bytes actually decompressed (headers can lie). Each entry is written to a ".part"
 * file and renamed only after it is complete, so a crash never leaves a valid-looking partial file.
 * Archive contents are never executed, and the source archive is never modified or deleted.
 */
class SafeZipExtractor(private val limits: ZipLimits = ZipLimits()) {

    fun extract(
        archive: InputStream,
        stagingDir: File,
        accept: (normalizedName: String) -> Boolean,
        isCancelled: () -> Boolean = { false },
        onProgress: (entriesSeen: Int, bytesWritten: Long) -> Unit = { _, _ -> },
    ): ZipExtractionResult {
        require(stagingDir.isDirectory || stagingDir.mkdirs()) { "Cannot create staging directory" }
        val outcomes = mutableListOf<ZipEntryOutcome>()
        val usedNames = HashSet<String>()
        var entries = 0
        var totalBytes = 0L
        val buffer = ByteArray(64 * 1024)

        try {
            ZipInputStream(archive.buffered()).use { zip ->
                while (true) {
                    if (isCancelled()) return ZipExtractionResult.Cancelled(outcomes)
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > limits.maxEntries) {
                        return ZipExtractionResult.Aborted(outcomes, "Archive has more than ${limits.maxEntries} entries")
                    }
                    val rawName = entry.name
                    if (entry.isDirectory) continue

                    val name = ZipPaths.normalizeEntryName(rawName)
                    if (name == null) {
                        outcomes += ZipEntryOutcome.Rejected(rawName, "Unsafe path")
                        continue
                    }
                    if (!accept(name)) {
                        outcomes += ZipEntryOutcome.Skipped(name, "Unsupported file type")
                        continue
                    }
                    // Flatten into staging with a unique file name; the archive's folder layout is irrelevant to the library.
                    val leaf = uniqueLeaf(name.substringAfterLast('/'), usedNames)
                    val target = ZipPaths.resolveInside(stagingDir, leaf)
                    if (target == null) {
                        outcomes += ZipEntryOutcome.Rejected(rawName, "Path escapes staging directory")
                        continue
                    }
                    val part = File(target.path + ".part")
                    var written = 0L
                    var failure: String? = null
                    part.outputStream().use { out ->
                        while (true) {
                            if (isCancelled()) break
                            val n = zip.read(buffer)
                            if (n < 0) break
                            written += n
                            totalBytes += n
                            if (written > limits.maxEntryBytes) { failure = "Entry exceeds ${limits.maxEntryBytes} bytes"; break }
                            if (totalBytes > limits.maxTotalBytes) { failure = "Archive exceeds ${limits.maxTotalBytes} extracted bytes"; break }
                            out.write(buffer, 0, n)
                        }
                    }
                    if (isCancelled()) {
                        part.delete()
                        return ZipExtractionResult.Cancelled(outcomes)
                    }
                    val compressed = entry.compressedSize
                    if (failure == null && compressed > 0 && written > limits.ratioCheckThresholdBytes &&
                        written / compressed > limits.maxCompressionRatio
                    ) {
                        failure = "Compression ratio is suspiciously high"
                    }
                    if (failure != null) {
                        part.delete()
                        outcomes += ZipEntryOutcome.Rejected(name, failure!!)
                        return ZipExtractionResult.Aborted(outcomes, failure!!)
                    }
                    if (!part.renameTo(target)) {
                        part.delete()
                        return ZipExtractionResult.Aborted(outcomes, "Could not finalize $name")
                    }
                    outcomes += ZipEntryOutcome.Extracted(name, target, written)
                    onProgress(entries, totalBytes)
                }
            }
        } catch (e: ZipException) {
            cleanupParts(stagingDir)
            return ZipExtractionResult.Aborted(outcomes, "Malformed archive: ${e.message ?: "unknown error"}")
        } catch (e: IOException) {
            cleanupParts(stagingDir)
            return ZipExtractionResult.Aborted(outcomes, "Read error: ${e.message ?: "unknown error"}")
        } catch (e: IllegalArgumentException) {
            // ZipInputStream throws this for malformed entry names in some encodings.
            cleanupParts(stagingDir)
            return ZipExtractionResult.Aborted(outcomes, "Malformed entry name")
        }
        return ZipExtractionResult.Completed(outcomes)
    }

    private fun uniqueLeaf(leaf: String, used: MutableSet<String>): String {
        val safeLeaf = leaf.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(150).ifBlank { "file" }
        if (used.add(safeLeaf.lowercase())) return safeLeaf
        val base = safeLeaf.substringBeforeLast('.')
        val ext = safeLeaf.substringAfterLast('.', "")
        var i = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            if (used.add(candidate.lowercase())) return candidate
            i++
        }
    }

    private fun cleanupParts(dir: File) {
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }
}
