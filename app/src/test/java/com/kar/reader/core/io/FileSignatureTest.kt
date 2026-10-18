package com.kar.reader.core.io

import com.kar.reader.core.model.DocumentFormat
import com.kar.reader.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSignatureTest {

    @Test
    fun detectsPdfByContentNotName() {
        val file = Fixtures.writeTemp(Fixtures.minimalPdf(), ".txt")
        assertEquals(FileSignature.Result.Supported(DocumentFormat.PDF), FileSignature.detect(file))
    }

    @Test
    fun detectsEpub() {
        val file = Fixtures.writeTemp(Fixtures.epub3(), ".bin")
        assertEquals(FileSignature.Result.Supported(DocumentFormat.EPUB), FileSignature.detect(file))
    }

    @Test
    fun detectsPlainZip() {
        val file = Fixtures.writeTemp(Fixtures.zipBytes(listOf("a.pdf" to Fixtures.minimalPdf())), ".zip")
        assertEquals(FileSignature.Result.ZipArchive, FileSignature.detect(file))
    }

    @Test
    fun rejectsOtherContent() {
        val file = Fixtures.writeTemp("just text".toByteArray(), ".pdf")
        assertTrue(FileSignature.detect(file) is FileSignature.Result.Unsupported)
        val empty = Fixtures.writeTemp(ByteArray(0), ".pdf")
        assertTrue(FileSignature.detect(empty) is FileSignature.Result.Unsupported)
    }

    @Test
    fun corruptZipIsUnsupported() {
        val file = Fixtures.writeTemp("PK\u0003\u0004garbage".toByteArray(), ".epub")
        assertTrue(FileSignature.detect(file) is FileSignature.Result.Unsupported)
    }

    @Test
    fun sha256IsStable() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.sha256("abc".byteInputStream()),
        )
    }
}
