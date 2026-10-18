package com.kar.reader.core.io

import com.kar.reader.core.model.DocumentFormat
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Identifies documents by their content, never by file name or a caller-supplied MIME type,
 * because both are untrusted.
 */
object FileSignature {

    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** PDF readers accept the header anywhere in the first 1024 bytes; we do the same. */
    private const val PDF_HEADER_WINDOW = 1024

    sealed interface Result {
        data class Supported(val format: DocumentFormat) : Result
        data object ZipArchive : Result
        data class Unsupported(val reason: String) : Result
    }

    fun looksLikePdf(head: ByteArray): Boolean = indexOf(head, PDF_MAGIC, PDF_HEADER_WINDOW) >= 0

    fun looksLikeZip(head: ByteArray): Boolean =
        head.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { head[it] == ZIP_MAGIC[it] }

    /** Inspects a fully written local file. */
    fun detect(file: File): Result {
        if (!file.isFile) return Result.Unsupported("File does not exist")
        if (file.length() == 0L) return Result.Unsupported("File is empty")
        val head = ByteArray(PDF_HEADER_WINDOW)
        val read = file.inputStream().use { input -> readFully(input, head) }
        val bytes = head.copyOf(read)
        if (looksLikePdf(bytes)) return Result.Supported(DocumentFormat.PDF)
        if (looksLikeZip(bytes)) {
            return try {
                ZipFile(file).use { zip ->
                    if (isEpub(zip)) Result.Supported(DocumentFormat.EPUB) else Result.ZipArchive
                }
            } catch (e: ZipException) {
                Result.Unsupported("Damaged ZIP container")
            } catch (e: IOException) {
                Result.Unsupported("Unreadable ZIP container")
            }
        }
        return Result.Unsupported("Not a PDF or EPUB file")
    }

    /**
     * An EPUB is a ZIP with a "mimetype" entry containing "application/epub+zip".
     * Some real-world files violate the "first, stored" rule, so we also accept a
     * META-INF/container.xml as evidence, matching what mainstream readers tolerate.
     */
    fun isEpub(zip: ZipFile): Boolean {
        val mimetype = zip.getEntry("mimetype")
        if (mimetype != null && mimetype.size in 1..256) {
            val text = zip.getInputStream(mimetype).use { it.readBytes().toString(Charsets.US_ASCII).trim() }
            if (text == DocumentFormat.EPUB.mimeType) return true
        }
        return zip.getEntry("META-INF/container.xml") != null
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, window: Int): Int {
        val limit = minOf(haystack.size, window) - needle.size
        outer@ for (i in 0..limit) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
