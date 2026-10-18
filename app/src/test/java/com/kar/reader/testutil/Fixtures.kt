package com.kar.reader.testutil

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Generated fixtures. No third-party documents are committed to the repository. */
object Fixtures {

    fun zipBytes(entries: List<Pair<String, ByteArray>>, storedFirst: Boolean = false): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            entries.forEachIndexed { index, (name, data) ->
                val entry = ZipEntry(name)
                if (storedFirst && index == 0) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** A tiny but structurally valid single-page PDF. */
    fun minimalPdf(): ByteArray = """%PDF-1.4
1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >> endobj
trailer << /Root 1 0 R >>
%%EOF
""".toByteArray(Charsets.US_ASCII)

    fun epub3(title: String = "Test Book", withNav: Boolean = true, withNcx: Boolean = false, rtl: Boolean = false): ByteArray {
        val manifestNav = if (withNav) """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" else ""
        val manifestNcx = if (withNcx) """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""" else ""
        val spineToc = if (withNcx) """ toc="ncx"""" else ""
        val ppd = if (rtl) """ page-progression-direction="rtl"""" else ""
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:1234</dc:identifier>
    <dc:title>$title</dc:title>
    <dc:creator>Ada Writer</dc:creator>
    <dc:creator>Bo Coauthor</dc:creator>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    $manifestNav
    $manifestNcx
    <item id="c1" href="text/chapter%201.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="text/chapter2.xhtml" media-type="application/xhtml+xml"/>
    <item id="cover" href="images/cover.png" media-type="image/png" properties="cover-image"/>
  </manifest>
  <spine$spineToc$ppd>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
  </spine>
</package>"""
        val nav = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<body><nav epub:type="toc"><ol>
  <li><a href="text/chapter%201.xhtml">Chapter One</a>
    <ol><li><a href="text/chapter%201.xhtml#s2">Section 1.2</a></li></ol>
  </li>
  <li><a href="text/chapter2.xhtml">Chapter Two</a></li>
</ol></nav></body></html>"""
        val ncx = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
    <navPoint id="p1"><navLabel><text>NCX One</text></navLabel><content src="text/chapter%201.xhtml"/></navPoint>
    <navPoint id="p2"><navLabel><text>NCX Two</text></navLabel><content src="text/chapter2.xhtml"/></navPoint>
  </navMap>
</ncx>"""
        val chapter = { n: Int -> """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Chapter $n</h1><p id="s2">Text $n</p></body></html>""" }
        val entries = mutableListOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""".toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray(),
            "OEBPS/text/chapter 1.xhtml" to chapter(1).toByteArray(),
            "OEBPS/text/chapter2.xhtml" to chapter(2).toByteArray(),
            "OEBPS/images/cover.png" to byteArrayOf(1, 2, 3),
        )
        if (withNav) entries += "OEBPS/nav.xhtml" to nav.toByteArray()
        if (withNcx) entries += "OEBPS/toc.ncx" to ncx.toByteArray()
        return zipBytes(entries, storedFirst = true)
    }

    fun tempDir(prefix: String): File = kotlin.io.path.createTempDirectory(prefix).toFile()

    fun writeTemp(bytes: ByteArray, suffix: String): File =
        File.createTempFile("kar", suffix).apply { writeBytes(bytes); deleteOnExit() }
}
