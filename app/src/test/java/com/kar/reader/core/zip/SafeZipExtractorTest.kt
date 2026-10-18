package com.kar.reader.core.zip

import com.kar.reader.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafeZipExtractorTest {

    private val acceptBooks: (String) -> Boolean = { it.endsWith(".pdf", true) || it.endsWith(".epub", true) }

    @Test
    fun extractsSupportedEntriesAndSkipsOthers() {
        val zip = Fixtures.zipBytes(
            listOf(
                "books/one.pdf" to Fixtures.minimalPdf(),
                "books/two.epub" to Fixtures.epub3(),
                "readme.txt" to "hello".toByteArray(),
            ),
        )
        val staging = Fixtures.tempDir("stage")
        val result = SafeZipExtractor().extract(zip.inputStream(), staging, acceptBooks)
        assertTrue(result is ZipExtractionResult.Completed)
        val extracted = result.outcomes.filterIsInstance<ZipEntryOutcome.Extracted>()
        assertEquals(2, extracted.size)
        assertEquals(1, result.outcomes.filterIsInstance<ZipEntryOutcome.Skipped>().size)
        extracted.forEach { assertTrue(it.file.isFile && it.file.parentFile!!.canonicalPath == staging.canonicalPath) }
        assertTrue(staging.listFiles()!!.none { it.name.endsWith(".part") })
        staging.deleteRecursively()
    }

    @Test
    fun pathTraversalCannotEscapeStaging() {
        val parent = Fixtures.tempDir("parent")
        val staging = File(parent, "staging").apply { mkdirs() }
        val zip = Fixtures.zipBytes(
            listOf(
                "../escaped.pdf" to Fixtures.minimalPdf(),
                "a/../../escaped2.pdf" to Fixtures.minimalPdf(),
                "/abs.pdf" to Fixtures.minimalPdf(),
                "ok.pdf" to Fixtures.minimalPdf(),
            ),
        )
        val result = SafeZipExtractor().extract(zip.inputStream(), staging, acceptBooks)
        assertEquals(3, result.outcomes.filterIsInstance<ZipEntryOutcome.Rejected>().size)
        assertEquals(1, result.outcomes.filterIsInstance<ZipEntryOutcome.Extracted>().size)
        assertFalse(File(parent, "escaped.pdf").exists())
        assertFalse(File(parent, "escaped2.pdf").exists())
        parent.deleteRecursively()
    }

    @Test
    fun enforcesEntryCountLimit() {
        val entries = (1..5).map { "b$it.pdf" to Fixtures.minimalPdf() }
        val staging = Fixtures.tempDir("stage")
        val result = SafeZipExtractor(ZipLimits(maxEntries = 3)).extract(Fixtures.zipBytes(entries).inputStream(), staging, acceptBooks)
        assertTrue(result is ZipExtractionResult.Aborted)
        assertEquals(3, result.outcomes.filterIsInstance<ZipEntryOutcome.Extracted>().size)
        staging.deleteRecursively()
    }

    @Test
    fun stopsDecompressionBombBySize() {
        val bomb = ByteArray(5 * 1024 * 1024) // 5 MiB of zeros compresses to a few KiB
        val zip = Fixtures.zipBytes(listOf("bomb.pdf" to bomb))
        val staging = Fixtures.tempDir("stage")
        val result = SafeZipExtractor(ZipLimits(maxEntryBytes = 1L shl 20)).extract(zip.inputStream(), staging, acceptBooks)
        assertTrue(result is ZipExtractionResult.Aborted)
        assertTrue(staging.listFiles()!!.isEmpty())
        staging.deleteRecursively()
    }

    @Test
    fun stopsDecompressionBombByRatio() {
        val bomb = ByteArray(8 * 1024 * 1024)
        val zip = Fixtures.zipBytes(listOf("bomb.pdf" to bomb))
        val staging = Fixtures.tempDir("stage")
        val result = SafeZipExtractor(ZipLimits(maxCompressionRatio = 50)).extract(zip.inputStream(), staging, acceptBooks)
        assertTrue(result is ZipExtractionResult.Aborted)
        assertTrue(staging.listFiles()!!.isEmpty())
        staging.deleteRecursively()
    }

    @Test
    fun malformedArchiveFailsSafely() {
        val garbage = "PK\u0003\u0004 this is not really a zip".toByteArray()
        val staging = Fixtures.tempDir("stage")
        val result = SafeZipExtractor().extract(garbage.inputStream(), staging, acceptBooks)
        assertFalse(result.outcomes.any { it is ZipEntryOutcome.Extracted })
        assertTrue(staging.listFiles()!!.isEmpty())
        staging.deleteRecursively()
    }

    @Test
    fun cancellationLeavesNoPartialFiles() {
        val entries = (1..4).map { "b$it.pdf" to Fixtures.minimalPdf() }
        val staging = Fixtures.tempDir("stage")
        var seen = 0
        val result = SafeZipExtractor().extract(
            Fixtures.zipBytes(entries).inputStream(),
            staging,
            acceptBooks,
            isCancelled = { seen >= 2 },
            onProgress = { count, _ -> seen = count },
        )
        assertTrue(result is ZipExtractionResult.Cancelled)
        assertTrue(staging.listFiles()!!.none { it.name.endsWith(".part") })
        assertEquals(2, staging.listFiles()!!.size)
        staging.deleteRecursively()
    }

    @Test
    fun duplicateLeafNamesDoNotOverwrite() {
        val zip = Fixtures.zipBytes(listOf("a/book.pdf" to Fixtures.minimalPdf(), "b/book.pdf" to Fixtures.minimalPdf()))
        val staging = Fixtures.tempDir("stage")
        val result = SafeZipExtractor().extract(zip.inputStream(), staging, acceptBooks)
        val files = result.outcomes.filterIsInstance<ZipEntryOutcome.Extracted>().map { it.file.name }.toSet()
        assertEquals(setOf("book.pdf", "book (2).pdf"), files)
        staging.deleteRecursively()
    }
}
