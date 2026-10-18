package com.kar.reader.core.epub

import com.kar.reader.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.zip.ZipFile

class EpubParserTest {

    private fun parse(bytes: ByteArray): EpubPublication {
        val file = Fixtures.writeTemp(bytes, ".epub")
        return ZipFile(file).use { EpubParser.parse(it) }
    }

    @Test
    fun parsesEpub3MetadataSpineAndNav() {
        val pub = parse(Fixtures.epub3(title = "Moby  Dick"))
        assertEquals("3.0", pub.version)
        assertEquals("Moby Dick", pub.title)
        assertEquals(listOf("Ada Writer", "Bo Coauthor"), pub.authors)
        assertEquals("en", pub.language)
        assertEquals("OEBPS/content.opf", pub.opfPath)
        assertEquals(listOf("OEBPS/text/chapter 1.xhtml", "OEBPS/text/chapter2.xhtml"), pub.spine.map { it.href })
        assertEquals("OEBPS/images/cover.png", pub.coverHref)
        assertEquals(3, pub.toc.size)
        assertEquals("Chapter One", pub.toc[0].title)
        assertEquals(0, pub.toc[0].depth)
        assertEquals("OEBPS/text/chapter 1.xhtml#s2", pub.toc[1].href)
        assertEquals(1, pub.toc[1].depth)
        assertEquals(1, pub.spineIndexOf(pub.toc[2].href))
        assertFalse(pub.pageProgressionRtl)
        assertFalse(pub.fixedLayout)
    }

    @Test
    fun fallsBackToNcxForEpub2StyleToc() {
        val pub = parse(Fixtures.epub3(withNav = false, withNcx = true))
        assertEquals(listOf("NCX One", "NCX Two"), pub.toc.map { it.title })
        assertEquals("OEBPS/text/chapter 1.xhtml", pub.toc[0].href)
    }

    @Test
    fun detectsRtlProgression() {
        assertTrue(parse(Fixtures.epub3(rtl = true)).pageProgressionRtl)
    }

    @Test
    fun missingContainerFailsWithClearError() {
        val bytes = Fixtures.zipBytes(listOf("mimetype" to "application/epub+zip".toByteArray()))
        try {
            parse(bytes)
            fail("Expected EpubParseException")
        } catch (e: EpubParseException) {
            assertTrue(e.message!!.contains("container.xml"))
        }
    }

    @Test
    fun malformedOpfFailsGracefully() {
        val bytes = Fixtures.zipBytes(
            listOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="x.opf"/></rootfiles></container>""".toByteArray(),
                "x.opf" to "<package><unclosed>".toByteArray(),
            ),
        )
        try {
            parse(bytes)
            fail("Expected EpubParseException")
        } catch (e: EpubParseException) {
            // expected
        }
    }

    @Test
    fun rejectsTraversalInRootfilePath() {
        val bytes = Fixtures.zipBytes(
            listOf(
                "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="../../x.opf"/></rootfiles></container>""".toByteArray(),
            ),
        )
        try {
            parse(bytes)
            fail("Expected EpubParseException")
        } catch (e: EpubParseException) {
            assertTrue(e.message!!.contains("Unsafe"))
        }
    }

    @Test
    fun doesNotExpandExternalEntities() {
        val secret = Fixtures.writeTemp("TOP-SECRET".toByteArray(), ".txt")
        val xxe = """<?xml version="1.0"?>
<!DOCTYPE container [ <!ENTITY xxe SYSTEM "file://${secret.absolutePath}"> ]>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="&xxe;"/></rootfiles></container>"""
        val bytes = Fixtures.zipBytes(listOf("META-INF/container.xml" to xxe.toByteArray()))
        try {
            val pub = parse(bytes)
            assertFalse(pub.opfPath.contains("TOP-SECRET"))
        } catch (e: EpubParseException) {
            assertFalse(e.message.orEmpty().contains("TOP-SECRET"))
        }
    }
}
