package com.kar.reader.feature.epub

import com.kar.reader.core.epub.EpubParser
import com.kar.reader.core.epub.EpubPublication
import com.kar.reader.core.zip.ZipPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Reader themes. Colours are applied to EPUB content as injected CSS. */
enum class ReaderTheme(val label: String, val background: String, val foreground: String, val link: String) {
    LIGHT("Light", "#FFFFFF", "#1A1A1A", "#1E5AA8"),
    SEPIA("Sepia", "#F4ECD8", "#3B2F22", "#7A4A10"),
    DARK("Dark", "#15171A", "#E6E2D8", "#8FB8EE"),
    CONTRAST("Contrast", "#000000", "#FFFF66", "#66FFFF"),
    EINK("E-ink", "#FFFFFF", "#000000", "#000000");

    companion object {
        fun from(name: String?): ReaderTheme = entries.firstOrNull { it.name == name } ?: LIGHT
    }
}

data class EpubResource(val mimeType: String, val encoding: String?, val stream: InputStream)

/**
 * An open EPUB. Serves its resources to the WebView from inside the ZIP. Paths are normalized and
 * resolved only inside the archive, entry sizes are capped, and nothing outside the book can be read.
 */
class EpubSession private constructor(private val zip: ZipFile, val publication: EpubPublication) : Closeable {

    companion object {
        const val HOST = "book.kar.local"
        const val BASE = "https://$HOST/"
        private const val MAX_RESOURCE_BYTES = 64L shl 20

        suspend fun open(file: File): EpubSession = withContext(Dispatchers.IO) {
            val zip = ZipFile(file)
            try {
                EpubSession(zip, EpubParser.parse(zip))
            } catch (e: Exception) {
                zip.close(); throw e
            }
        }

        private val MIME_BY_EXT = mapOf(
            "xhtml" to "application/xhtml+xml", "html" to "text/html", "htm" to "text/html",
            "css" to "text/css", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "svg" to "image/svg+xml", "webp" to "image/webp",
            "ttf" to "font/ttf", "otf" to "font/otf", "woff" to "font/woff", "woff2" to "font/woff2",
            "mp3" to "audio/mpeg", "mp4" to "video/mp4", "xml" to "application/xml", "ncx" to "application/x-dtbncx+xml",
        )
    }

    val spineHrefs: List<String> get() = publication.spine.map { it.href }

    fun urlFor(href: String): String = BASE + href.split('/').joinToString("/") { android.net.Uri.encode(it) }

    /** Returns the resource for a request path, or null when it doesn't exist or isn't allowed. */
    fun resource(path: String, chapterCss: String?): EpubResource? {
        val decoded = android.net.Uri.decode(path.removePrefix("/"))
        val name = ZipPaths.normalizeEntryName(decoded) ?: return null
        val entry = zip.getEntry(name) ?: return null
        if (entry.isDirectory || entry.size > MAX_RESOURCE_BYTES) return null
        val manifestType = publication.manifest.values.firstOrNull { it.href == name }?.mediaType
        val mime = manifestType?.takeIf { it.isNotBlank() } ?: MIME_BY_EXT[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
        val isChapter = mime.contains("html")
        if (isChapter && chapterCss != null) {
            val text = zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
            return EpubResource(mime, "utf-8", ByteArrayInputStream(injectCss(text, chapterCss).toByteArray(Charsets.UTF_8)))
        }
        return EpubResource(mime, if (mime.startsWith("text/")) "utf-8" else null, zip.getInputStream(entry))
    }

    private fun injectCss(html: String, css: String): String {
        val style = "<style id=\"kar-reader-style\">$css</style>"
        val headClose = Regex("</head\\s*>", RegexOption.IGNORE_CASE).find(html)
        return if (headClose != null) html.substring(0, headClose.range.first) + style + html.substring(headClose.range.first)
        else style + html
    }

    /** Title of the table-of-contents entry for [spineIndex], or "Chapter N". */
    fun chapterLabel(spineIndex: Int): String {
        val href = spineHrefs.getOrNull(spineIndex) ?: return "Chapter ${spineIndex + 1}"
        return publication.toc.firstOrNull { it.href.substringBefore('#') == href }?.title ?: "Chapter ${spineIndex + 1}"
    }

    override fun close() { runCatching { zip.close() } }
}

/** CSS injected into every chapter. User typography wins over publisher styles; layout structure is untouched. */
fun readerCss(theme: ReaderTheme, lineHeight: Float, marginEm: Float): String = """
html { background: ${theme.background} !important; }
body { background: ${theme.background} !important; color: ${theme.foreground} !important;
  line-height: $lineHeight !important; margin: 0 auto !important; padding: 1.2em ${marginEm}em 4em ${marginEm}em !important;
  max-width: 44em; word-wrap: break-word; -webkit-text-size-adjust: 100%; }
p, li, blockquote, dd, td { line-height: $lineHeight !important; }
a { color: ${theme.link} !important; }
img, svg, video { max-width: 100% !important; height: auto !important; }
pre { white-space: pre-wrap; }
mark[data-kar] { color: inherit !important; border-radius: 2px; }
${if (theme == ReaderTheme.EINK || theme == ReaderTheme.CONTRAST) "* { color: ${theme.foreground} !important; background-color: transparent !important; } html, body { background: ${theme.background} !important; } img { filter: grayscale(100%) contrast(1.2); }" else ""}
""".trimIndent()

/** JavaScript helpers run with evaluateJavascript. Publisher scripts are blocked by the page's CSP. */
const val READER_JS = """
(function(){
  if (window.__kar) return;
  function textNodes(){ var w=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null); var n, a=[]; while((n=w.nextNode())) a.push(n); return a; }
  window.__kar = {
    selection: function(){
      var s = window.getSelection(); if (!s || s.rangeCount === 0 || s.isCollapsed) return null;
      var r = s.getRangeAt(0); var pre = document.createRange(); pre.setStart(document.body, 0); pre.setEnd(r.startContainer, r.startOffset);
      var text = r.toString(); return JSON.stringify({ start: pre.toString().length, length: text.length, text: text });
    },
    clearSelection: function(){ var s = window.getSelection(); if (s) s.removeAllRanges(); },
    highlight: function(id, start, length, color){
      var nodes = textNodes(); var pos = 0; var end = start + length;
      for (var i = 0; i < nodes.length; i++) {
        var n = nodes[i]; var len = n.nodeValue.length; var ns = pos, ne = pos + len; pos = ne;
        if (ne <= start || ns >= end) continue;
        var s = Math.max(start, ns) - ns; var e = Math.min(end, ne) - ns;
        var t = n; if (s > 0) t = n.splitText(s); if (e - s < t.nodeValue.length) t.splitText(e - s);
        var m = document.createElement('mark'); m.setAttribute('data-kar', id); m.style.background = color;
        t.parentNode.insertBefore(m, t); m.appendChild(t);
      }
    },
    clearHighlights: function(){
      var marks = document.querySelectorAll('mark[data-kar]');
      for (var i = 0; i < marks.length; i++) { var m = marks[i]; var p = m.parentNode; while (m.firstChild) p.insertBefore(m.firstChild, m); p.removeChild(m); p.normalize(); }
    },
    progress: function(){ var h = document.documentElement.scrollHeight - window.innerHeight; return h > 0 ? Math.min(1, Math.max(0, window.scrollY / h)) : 0; },
    setProgress: function(p){ var h = document.documentElement.scrollHeight - window.innerHeight; window.scrollTo(0, Math.max(0, h * p)); },
    page: function(dir){ var before = window.scrollY; window.scrollBy(0, dir * window.innerHeight * 0.92); return Math.abs(window.scrollY - before) > 2; },
    atEnd: function(){ return window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 4; },
    find: function(q){ if (!q) return 0; return window.find(q, false, false, true, false, false, false) ? 1 : 0; }
  };
})();
"""
