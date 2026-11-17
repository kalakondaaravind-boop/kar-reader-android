package com.kar.reader.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.kar.reader.core.epub.EpubParser
import com.kar.reader.core.epub.EpubPublication
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.PDFTextStripperByArea
import java.awt.geom.Rectangle2D
import java.io.Closeable
import java.io.File
import java.util.Calendar
import java.util.zip.ZipFile
import javax.imageio.ImageIO

object BookReaders {
    class Meta(val title: String, val authors: List<String>, val year: String?, val publisher: String?, val units: Int)

    fun pdfMeta(file: File, id: String, fallbackTitle: String): Meta {
        Loader.loadPDF(file).use { doc ->
            val info = doc.documentInformation
            runCatching { ImageIO.write(PDFRenderer(doc).renderImage(0, 0.45f), "png", File(Paths.covers, "$id.png")) }
            return Meta(
                title = info?.title?.trim().takeUnless { it.isNullOrBlank() } ?: fallbackTitle.replace('_', ' '),
                authors = info?.author.orEmpty().split(';', ',').map { it.trim() }.filter { it.isNotEmpty() },
                year = runCatching { info?.creationDate?.get(Calendar.YEAR)?.toString() }.getOrNull(),
                publisher = null,
                units = doc.numberOfPages,
            )
        }
    }

    fun epubMeta(file: File, id: String, fallbackTitle: String): Meta {
        ZipFile(file).use { zip ->
            val pub = EpubParser.parse(zip)
            pub.coverHref?.let { href ->
                runCatching {
                    val entry = zip.getEntry(href)
                    if (entry != null) zip.getInputStream(entry).use { ins ->
                        ImageIO.read(ins)?.let { ImageIO.write(it, "png", File(Paths.covers, "$id.png")) }
                    }
                }
            }
            return Meta(pub.title?.takeIf { it.isNotBlank() } ?: fallbackTitle, pub.authors, null, null, pub.spine.count { it.linear }.coerceAtLeast(1))
        }
    }

    fun coverFor(id: String): ImageBitmap? {
        val f = File(Paths.covers, "$id.png")
        return if (f.isFile) runCatching { ImageIO.read(f).toComposeImageBitmap() }.getOrNull() else null
    }
}

/** An open PDF. PDFBox isn't thread-safe, so every call takes the document's lock. */
class PdfBook(file: File) : Closeable {
    private val doc: PDDocument = Loader.loadPDF(file)
    private val renderer = PDFRenderer(doc)
    private val textCache = HashMap<Int, String>()
    val pageCount: Int = doc.numberOfPages

    /** Page size in points, as displayed (rotation applied). */
    fun pageSize(i: Int): Pair<Float, Float> = synchronized(doc) {
        val page = doc.getPage(i)
        val box = page.cropBox
        if (page.rotation % 180 == 90) box.height to box.width else box.width to box.height
    }

    fun render(i: Int, scale: Float): ImageBitmap = synchronized(doc) { renderer.renderImage(i, scale).toComposeImageBitmap() }

    fun text(i: Int): String = synchronized(textCache) { textCache[i] }
        ?: synchronized(doc) {
            val stripper = PDFTextStripper().apply { startPage = i + 1; endPage = i + 1 }
            stripper.getText(doc).trim()
        }.also { synchronized(textCache) { textCache[i] = it } }

    /** The text inside a rectangle given as fractions of the page. Empty when the page has no text layer there. */
    fun textInArea(i: Int, rx: Float, ry: Float, rw: Float, rh: Float): String = runCatching {
        synchronized(doc) {
            val (w, h) = pageSize(i)
            val stripper = PDFTextStripperByArea()
            stripper.addRegion("r", Rectangle2D.Float(rx * w, ry * h, rw * w, rh * h))
            stripper.extractRegions(doc.getPage(i))
            stripper.getTextForRegion("r").replace(Regex("\\s+"), " ").trim()
        }
    }.getOrDefault("")

    /** The document's own outline (bookmarks pane), flattened. Empty when the PDF has none. */
    fun outline(): List<TocItem> = runCatching {
        synchronized(doc) {
            val out = ArrayList<TocItem>()
            val root = doc.documentCatalog.documentOutline ?: return@synchronized out
            fun walk(node: org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode, depth: Int) {
                var c = node.firstChild
                while (c != null) {
                    val page = runCatching { c!!.findDestinationPage(doc) }.getOrNull()
                    val idx = if (page != null) doc.pages.indexOf(page) else -1
                    if (idx >= 0) out += TocItem(c.title ?: "", idx, depth)
                    walk(c, depth + 1)
                    c = c.nextSibling
                }
            }
            walk(root, 0)
            out
        }
    }.getOrDefault(emptyList())

    fun hasTextLayer(): Boolean = (0 until minOf(pageCount, 5)).any { text(it).length > 20 }

    override fun close() = doc.close()
}

class TocItem(val title: String, val unit: Int, val depth: Int)

class EpubChapter(val href: String, val title: String)

/** An open EPUB: chapter list and chapter text. Images and complex layout aren't drawn in this version. */
class EpubBook(file: File) : Closeable {
    private val zip = ZipFile(file)
    val pub: EpubPublication = EpubParser.parse(zip)
    val chapters: List<EpubChapter>
    private val cache = HashMap<Int, String>()

    init {
        val spine = pub.spine.filter { it.linear }.ifEmpty { pub.spine }
        val titles = HashMap<String, String>()
        pub.toc.forEach { titles.putIfAbsent(it.href.substringBefore('#'), it.title) }
        chapters = spine.mapIndexed { i, s -> EpubChapter(s.href, titles[s.href] ?: "Section ${i + 1}") }
    }

    fun text(i: Int): String = cache.getOrPut(i) {
        val entry = zip.getEntry(chapters[i].href) ?: return@getOrPut ""
        HtmlText.toPlain(zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) })
    }

    override fun close() = zip.close()
}

object HtmlText {
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "copy" to "©", "laquo" to "«", "raquo" to "»",
    )

    fun toPlain(html: String): String {
        var s = html
        s = Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>").replace(s, "")
        s = Regex("\\s+").replace(s, " ")
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        s = Regex("(?i)<li[^>]*>").replace(s, "• ")
        s = Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote|section|article|pre|ul|ol|table)>").replace(s, "\n\n")
        s = Regex("<[^>]+>").replace(s, "")
        s = decode(s).replace(' ', ' ')
        s = Regex("[ \\t]+").replace(s, " ")
        s = Regex(" *\n *").replace(s, "\n")
        s = Regex("\n{3,}").replace(s, "\n\n")
        return s.trim()
    }

    private fun decode(s: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> named[e] ?: m.value
        }
    }
}
