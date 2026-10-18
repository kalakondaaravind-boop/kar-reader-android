package com.kar.reader.core.zip

import java.io.File

/**
 * Path rules shared by ZIP import and the EPUB resource loader. Archive entry names are
 * untrusted: they may contain "..", absolute paths, drive letters, backslashes or NUL bytes.
 */
object ZipPaths {

    /**
     * Returns a normalized, relative, forward-slash path, or null if the name is unsafe.
     * Normalization resolves "." and "a/../b" segments but rejects any ".." that would
     * climb above the archive root.
     */
    fun normalizeEntryName(name: String): String? {
        if (name.isEmpty() || name.length > 1024) return null
        if (name.any { it == '\u0000' || it == '\\' }) return null
        if (name.startsWith("/")) return null
        if (name.length >= 2 && name[1] == ':') return null // Windows drive letter
        val out = ArrayDeque<String>()
        for (segment in name.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isEmpty()) return null else out.removeLast()
                else -> out.addLast(segment)
            }
        }
        if (out.isEmpty()) return null
        return out.joinToString("/")
    }

    /**
     * Resolves [relativePath] (already normalized) under [root] and verifies, using canonical
     * paths, that the result stays inside [root]. This also defeats symlinked directories.
     */
    fun resolveInside(root: File, relativePath: String): File? {
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath).canonicalFile
        val rootPath = canonicalRoot.path + File.separator
        return if (target.path.startsWith(rootPath)) target else null
    }

    /** Resolves an href found inside [baseEntry] (e.g. an OPF or XHTML file) to an archive entry name. */
    fun resolveHref(baseEntry: String, href: String): String? {
        val withoutFragment = href.substringBefore('#').substringBefore('?')
        if (withoutFragment.isEmpty()) return null
        if (withoutFragment.contains("://") || withoutFragment.startsWith("data:")) return null
        val decoded = percentDecode(withoutFragment) ?: return null
        val baseDir = baseEntry.substringBeforeLast('/', missingDelimiterValue = "")
        val joined = if (decoded.startsWith("/")) decoded.trimStart('/') else if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
        return normalizeEntryName(joined)
    }

    private fun percentDecode(s: String): String? {
        if (!s.contains('%')) return s
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes.write(v)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
