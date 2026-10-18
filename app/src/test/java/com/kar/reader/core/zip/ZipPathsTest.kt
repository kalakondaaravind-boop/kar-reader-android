package com.kar.reader.core.zip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ZipPathsTest {

    @Test
    fun normalizesSafeNames() {
        assertEquals("a/b.pdf", ZipPaths.normalizeEntryName("a/b.pdf"))
        assertEquals("b.pdf", ZipPaths.normalizeEntryName("a/../b.pdf"))
        assertEquals("a/b.pdf", ZipPaths.normalizeEntryName("./a//b.pdf"))
    }

    @Test
    fun rejectsTraversalAndAbsolutePaths() {
        listOf(
            "../evil.pdf",
            "a/../../evil.pdf",
            "/etc/passwd",
            "C:/Windows/evil.pdf",
            "a\\..\\evil.pdf",
            "nul\u0000.pdf",
            "",
            "./",
        ).forEach { assertNull("should reject '$it'", ZipPaths.normalizeEntryName(it)) }
    }

    @Test
    fun resolveInsideKeepsFilesInRoot() {
        val root = kotlin.io.path.createTempDirectory("zp").toFile()
        assertNotNull(ZipPaths.resolveInside(root, "book.pdf"))
        assertNull(ZipPaths.resolveInside(root, "../outside.pdf"))
        assertNull(ZipPaths.resolveInside(root, "x/../../outside.pdf"))
        root.deleteRecursively()
    }

    @Test
    fun resolvesHrefsRelativeToBase() {
        assertEquals("OEBPS/text/ch 1.xhtml", ZipPaths.resolveHref("OEBPS/content.opf", "text/ch%201.xhtml#frag"))
        assertEquals("OEBPS/img/a.png", ZipPaths.resolveHref("OEBPS/text/ch1.xhtml", "../img/a.png"))
        assertNull(ZipPaths.resolveHref("OEBPS/text/ch1.xhtml", "https://example.com/x.png"))
        assertNull(ZipPaths.resolveHref("OEBPS/content.opf", "../../escape.xhtml"))
        assertNull(ZipPaths.resolveHref("OEBPS/content.opf", "#only-fragment"))
    }

    @Suppress("unused")
    private fun unused(f: File) = f
}
