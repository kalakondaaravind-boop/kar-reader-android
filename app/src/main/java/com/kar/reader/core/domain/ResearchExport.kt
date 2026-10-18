package com.kar.reader.core.domain

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class ExportHighlight(
    val text: String,
    val location: String?,
    val meaning: String?,
    val note: String?,
    val createdAt: Long,
)

data class ExportNote(val title: String, val body: String, val location: String?, val updatedAt: Long)

data class ExportBook(
    val id: String,
    val title: String,
    val authors: List<String>,
    val year: String?,
    val publisher: String?,
    val format: String,
    val tags: List<String>,
    val highlights: List<ExportHighlight>,
    val notes: List<ExportNote>,
) {
    val citation: CitationSource get() = CitationSource(authors, title, year, publisher)
}

/** Converts the user's own notes and highlights into portable formats. Source documents are never included. */
object ResearchExport {

    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

    fun markdown(book: ExportBook, style: CitationStyle = CitationStyle.APA): String = buildString {
        append("# ").append(book.title).append("\n\n")
        if (book.authors.isNotEmpty()) append("**Author:** ").append(book.authors.joinToString("; ")).append("  \n")
        if (book.year != null) append("**Year:** ").append(book.year).append("  \n")
        append("**Reference:** ").append(Citations.bibliography(book.citation, style) { "*$it*" }).append("\n\n")
        if (book.highlights.isNotEmpty()) {
            append("## Highlights\n\n")
            book.highlights.groupBy { it.meaning ?: "Highlights" }.forEach { (meaning, items) ->
                if (book.highlights.any { it.meaning != null }) append("### ").append(meaning).append("\n\n")
                items.forEach { h ->
                    append("> ").append(h.text.trim().replace("\n", "\n> ")).append("\n")
                    append(">\n> — ").append(Citations.inText(book.citation, style, h.location)).append("\n\n")
                    if (!h.note.isNullOrBlank()) append(h.note.trim()).append("\n\n")
                }
            }
        }
        if (book.notes.isNotEmpty()) {
            append("## Notes\n\n")
            book.notes.forEach { n ->
                append("### ").append(n.title.ifBlank { "Untitled note" }).append("\n\n")
                if (n.location != null) append("*").append(n.location).append("*\n\n")
                append(n.body.trim()).append("\n\n")
            }
        }
    }

    /** Obsidian note: Markdown with YAML front matter so Dataview and tags work. */
    fun obsidian(book: ExportBook, style: CitationStyle = CitationStyle.APA): String = buildString {
        append("---\n")
        append("title: ").append(yaml(book.title)).append('\n')
        if (book.authors.isNotEmpty()) {
            append("authors:\n")
            book.authors.forEach { append("  - ").append(yaml(it)).append('\n') }
        }
        if (book.year != null) append("year: ").append(yaml(book.year)).append('\n')
        append("format: ").append(book.format.lowercase()).append('\n')
        val tags = (listOf("kar-reader") + book.tags.map { tagSlug(it) }).filter { it.isNotEmpty() }.distinct()
        append("tags:\n")
        tags.forEach { append("  - ").append(it).append('\n') }
        append("source: KAR READER\n")
        append("---\n\n")
        append(markdown(book, style))
    }

    fun obsidianFileName(book: ExportBook): String = safeFileName(book.title).ifEmpty { "Untitled" } + ".md"

    fun bibtex(books: List<ExportBook>): String {
        val used = HashSet<String>()
        return books.joinToString("\n") { book ->
            var key = citeKey(book)
            var i = 2
            while (!used.add(key)) key = citeKey(book) + (i++).toString()
            buildString {
                append("@book{").append(key).append(",\n")
                append("  title = {").append(bibEscape(book.title)).append("},\n")
                if (book.authors.isNotEmpty()) append("  author = {").append(book.authors.joinToString(" and ") { bibEscape(it) }).append("},\n")
                if (book.year != null) append("  year = {").append(bibEscape(book.year)).append("},\n")
                if (book.publisher != null) append("  publisher = {").append(bibEscape(book.publisher)).append("},\n")
                if (book.tags.isNotEmpty()) append("  keywords = {").append(book.tags.joinToString(", ") { bibEscape(it) }).append("},\n")
                append("  note = {").append(book.highlights.size).append(" highlights exported from KAR READER}\n")
                append("}\n")
            }
        }
    }

    /** One row per highlight or note (RFC 4180). */
    fun csv(books: List<ExportBook>): String = buildString {
        append("book,authors,year,type,meaning,location,text,note,date\r\n")
        books.forEach { b ->
            b.highlights.forEach { h ->
                row(listOf(b.title, b.authors.joinToString("; "), b.year.orEmpty(), "highlight", h.meaning.orEmpty(), h.location.orEmpty(), h.text, h.note.orEmpty(), dateFmt.format(Instant.ofEpochMilli(h.createdAt))))
            }
            b.notes.forEach { n ->
                row(listOf(b.title, b.authors.joinToString("; "), b.year.orEmpty(), "note", "", n.location.orEmpty(), n.title, n.body, dateFmt.format(Instant.ofEpochMilli(n.updatedAt))))
            }
        }
    }

    private fun StringBuilder.row(cells: List<String>) {
        append(cells.joinToString(",") { csvCell(it) }).append("\r\n")
    }

    fun csvCell(value: String): String {
        // Neutralise spreadsheet formula injection, then quote per RFC 4180.
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun citeKey(book: ExportBook): String {
        val last = book.authors.firstOrNull()?.let { PersonName.parse(it).last } ?: "anon"
        val word = book.title.split(Regex("\\s+")).firstOrNull { it.length > 3 && it.lowercase() !in setOf("the", "and", "with", "from") } ?: book.title
        return (ascii(last) + (book.year?.take(4) ?: "") + ascii(word)).lowercase().ifEmpty { "book" }
    }

    fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim().take(120)

    private fun ascii(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("[^A-Za-z0-9]"), "")

    private fun tagSlug(s: String): String = s.trim().lowercase().replace(Regex("[^a-z0-9/_-]+"), "-").trim('-')

    private fun bibEscape(s: String): String = s.replace("\\", "\\textbackslash{}").replace("{", "\\{").replace("}", "\\}").replace("&", "\\&").replace("%", "\\%").replace("$", "\\$").replace("#", "\\#").replace("_", "\\_")

    private fun yaml(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
