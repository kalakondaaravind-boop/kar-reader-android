package com.kar.reader.core.epub

import com.kar.reader.core.zip.ZipPaths
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class EpubManifestItem(val id: String, val href: String, val mediaType: String, val properties: Set<String>)

data class EpubSpineItem(val idref: String, val href: String, val mediaType: String, val linear: Boolean)

data class EpubTocEntry(val title: String, val href: String, val depth: Int)

data class EpubPublication(
    val version: String,
    val title: String?,
    val authors: List<String>,
    val language: String?,
    val identifier: String?,
    val opfPath: String,
    val manifest: Map<String, EpubManifestItem>,
    val spine: List<EpubSpineItem>,
    val toc: List<EpubTocEntry>,
    val coverHref: String?,
    val pageProgressionRtl: Boolean,
    val fixedLayout: Boolean,
) {
    /** Index of the spine item containing [href] (fragment ignored), or -1. */
    fun spineIndexOf(href: String): Int {
        val path = href.substringBefore('#')
        return spine.indexOfFirst { it.href == path }
    }
}

class EpubParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimal, dependency-free EPUB 2/3 container parser: container.xml -> OPF -> manifest, spine,
 * metadata, and table of contents (EPUB 3 nav document, falling back to EPUB 2 NCX).
 *
 * Security: entity expansion and DTD loading are disabled where the platform parser supports it,
 * every referenced path is normalized through [ZipPaths], and entry sizes are capped.
 */
object EpubParser {

    private const val MAX_XML_BYTES = 8L shl 20 // 8 MiB per metadata document

    private const val NS_OPF = "http://www.idpf.org/2007/opf"
    private const val NS_DC = "http://purl.org/dc/elements/1.1/"
    private const val NS_CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"
    private const val NS_OPS = "http://www.idpf.org/2007/ops"

    fun parse(zip: ZipFile): EpubPublication {
        val container = readXml(zip, "META-INF/container.xml")
            ?: throw EpubParseException("Missing META-INF/container.xml")
        val rootfile = descendants(container.documentElement)
            .filter { it.localNameOrTag() == "rootfile" && (it.namespaceURI == null || it.namespaceURI == NS_CONTAINER) }
            .firstOrNull { it.getAttribute("media-type").ifEmpty { "application/oebps-package+xml" } == "application/oebps-package+xml" }
            ?: throw EpubParseException("container.xml has no rootfile")
        val opfPath = ZipPaths.normalizeEntryName(rootfile.getAttribute("full-path"))
            ?: throw EpubParseException("Unsafe or empty OPF path")
        val opf = readXml(zip, opfPath) ?: throw EpubParseException("Missing package document $opfPath")
        val pkg = opf.documentElement
        if (pkg.localNameOrTag() != "package") throw EpubParseException("Package document root is not <package>")
        val version = pkg.getAttribute("version").ifEmpty { "2.0" }

        val metadata = elements(pkg, NS_OPF, "metadata").firstOrNull()
        val title = metadata?.let { m -> elements(m, NS_DC, "title").firstOrNull()?.textContent?.cleanText() }
        val authors = metadata?.let { m -> elements(m, NS_DC, "creator").mapNotNull { it.textContent?.cleanText() } }.orEmpty()
        val language = metadata?.let { m -> elements(m, NS_DC, "language").firstOrNull()?.textContent?.cleanText() }
        val identifier = metadata?.let { m -> elements(m, NS_DC, "identifier").firstOrNull()?.textContent?.cleanText() }
        val metaElements = metadata?.let { elements(it, NS_OPF, "meta") }.orEmpty()
        val fixedLayout = metaElements.any {
            it.getAttribute("property") == "rendition:layout" && it.textContent.trim() == "pre-paginated"
        }

        val manifest = LinkedHashMap<String, EpubManifestItem>()
        elements(pkg, NS_OPF, "manifest").firstOrNull()?.let { m ->
            for (item in elements(m, NS_OPF, "item")) {
                val id = item.getAttribute("id")
                val href = ZipPaths.resolveHref(opfPath, item.getAttribute("href")) ?: continue
                if (id.isEmpty()) continue
                manifest[id] = EpubManifestItem(
                    id = id,
                    href = href,
                    mediaType = item.getAttribute("media-type"),
                    properties = item.getAttribute("properties").split(' ').filter { it.isNotBlank() }.toSet(),
                )
            }
        }

        val spineElement = elements(pkg, NS_OPF, "spine").firstOrNull()
            ?: throw EpubParseException("Package document has no spine")
        val spine = elements(spineElement, NS_OPF, "itemref").mapNotNull { ref ->
            val item = manifest[ref.getAttribute("idref")] ?: return@mapNotNull null
            EpubSpineItem(item.id, item.href, item.mediaType, linear = ref.getAttribute("linear") != "no")
        }
        if (spine.isEmpty()) throw EpubParseException("Spine is empty")
        val rtl = spineElement.getAttribute("page-progression-direction") == "rtl"

        val coverHref = manifest.values.firstOrNull { "cover-image" in it.properties }?.href
            ?: metaElements.firstOrNull { it.getAttribute("name") == "cover" }
                ?.getAttribute("content")?.let { manifest[it]?.href }

        val navItem = manifest.values.firstOrNull { "nav" in it.properties }
        var toc = navItem?.let { runCatching { parseNav(zip, it.href) }.getOrNull() }.orEmpty()
        if (toc.isEmpty()) {
            val ncxItem = manifest[spineElement.getAttribute("toc")]
                ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            toc = ncxItem?.let { runCatching { parseNcx(zip, it.href) }.getOrNull() }.orEmpty()
        }

        return EpubPublication(
            version = version,
            title = title,
            authors = authors,
            language = language,
            identifier = identifier,
            opfPath = opfPath,
            manifest = manifest,
            spine = spine,
            toc = toc,
            coverHref = coverHref,
            pageProgressionRtl = rtl,
            fixedLayout = fixedLayout,
        )
    }

    private fun parseNav(zip: ZipFile, navHref: String): List<EpubTocEntry> {
        val doc = readXml(zip, navHref) ?: return emptyList()
        val navs = descendants(doc.documentElement).filter { it.localNameOrTag() == "nav" }
        val tocNav = navs.firstOrNull { el ->
            el.getAttributeNS(NS_OPS, "type").split(' ').contains("toc") ||
                el.getAttribute("epub:type").split(' ').contains("toc")
        } ?: navs.firstOrNull() ?: return emptyList()
        val list = childElements(tocNav).firstOrNull { it.localNameOrTag() == "ol" } ?: return emptyList()
        val out = mutableListOf<EpubTocEntry>()
        walkNavList(list, navHref, 0, out)
        return out
    }

    private fun walkNavList(ol: Element, base: String, depth: Int, out: MutableList<EpubTocEntry>) {
        for (li in childElements(ol).filter { it.localNameOrTag() == "li" }) {
            val children = childElements(li)
            val anchor = children.firstOrNull { it.localNameOrTag() == "a" }
            if (anchor != null) {
                val href = ZipPaths.resolveHref(base, anchor.getAttribute("href"))
                val label = anchor.textContent.cleanText()
                if (href != null && label.isNotEmpty()) {
                    val fragment = anchor.getAttribute("href").substringAfter('#', "")
                    out += EpubTocEntry(label, if (fragment.isEmpty()) href else "$href#$fragment", depth)
                }
            }
            children.firstOrNull { it.localNameOrTag() == "ol" }?.let { walkNavList(it, base, depth + 1, out) }
        }
    }

    private fun parseNcx(zip: ZipFile, ncxHref: String): List<EpubTocEntry> {
        val doc = readXml(zip, ncxHref) ?: return emptyList()
        val navMap = descendants(doc.documentElement).firstOrNull { it.localNameOrTag() == "navMap" } ?: return emptyList()
        val out = mutableListOf<EpubTocEntry>()
        fun walk(parent: Element, depth: Int) {
            for (point in childElements(parent).filter { it.localNameOrTag() == "navPoint" }) {
                val label = descendants(point).firstOrNull { it.localNameOrTag() == "text" }?.textContent?.cleanText().orEmpty()
                val src = childElements(point).firstOrNull { it.localNameOrTag() == "content" }?.getAttribute("src").orEmpty()
                val href = ZipPaths.resolveHref(ncxHref, src)
                if (href != null && label.isNotEmpty()) {
                    val fragment = src.substringAfter('#', "")
                    out += EpubTocEntry(label, if (fragment.isEmpty()) href else "$href#$fragment", depth)
                }
                walk(point, depth + 1)
            }
        }
        walk(navMap, 0)
        return out
    }

    // ---- XML helpers -------------------------------------------------------------------------

    private fun readXml(zip: ZipFile, entryName: String): Document? {
        val entry = zip.getEntry(entryName) ?: return null
        if (entry.size > MAX_XML_BYTES) throw EpubParseException("$entryName is too large")
        return try {
            zip.getInputStream(entry).use { parseXml(it) }
        } catch (e: EpubParseException) {
            throw e
        } catch (e: IOException) {
            throw EpubParseException("Cannot read $entryName", e)
        } catch (e: Exception) {
            throw EpubParseException("Malformed XML in $entryName", e)
        }
    }

    internal fun parseXml(input: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isValidating = false
        factory.isExpandEntityReferences = false
        // Not every platform parser supports these features (Android's does not); ignore if rejected.
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to false,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        ).forEach { (feature, value) -> runCatching { factory.setFeature(feature, value) } }
        val builder = factory.newDocumentBuilder()
        // Never fetch external entities or DTDs (e.g. XHTML DTDs referenced by nav documents).
        builder.setEntityResolver { _, _ -> InputSource(java.io.StringReader("")) }
        builder.setErrorHandler(null)
        return builder.parse(InputSource(input))
    }

    private fun Node.localNameOrTag(): String = localName ?: nodeName.substringAfter(':')

    private fun childElements(parent: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(out::add)
        return out
    }

    private fun descendants(root: Element): Sequence<Element> = sequence {
        val stack = ArrayDeque<Element>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val el = stack.removeLast()
            yield(el)
            childElements(el).asReversed().forEach(stack::addLast)
        }
    }

    /** Direct children matching namespace + local name; falls back to local name for sloppy files without namespaces. */
    private fun elements(parent: Element, ns: String, local: String): List<Element> =
        childElements(parent).filter { it.localNameOrTag() == local && (it.namespaceURI == null || it.namespaceURI == ns) }

    private fun String.cleanText(): String = replace(Regex("\\s+"), " ").trim()
}
