package com.kar.reader.core.domain

enum class CitationStyle(val label: String) { APA("APA 7"), MLA("MLA 9"), CHICAGO("Chicago") }

/** Bibliographic facts for one book. Every field except [title] may be missing. */
data class CitationSource(
    val authors: List<String>,
    val title: String,
    val year: String? = null,
    val publisher: String? = null,
    val place: String? = null,
)

/** A parsed personal name. Organisations or single names keep everything in [last]. */
data class PersonName(val first: String, val last: String) {
    val initials: String
        get() = first.split(Regex("[\\s.-]+")).filter { it.isNotBlank() }.joinToString(" ") { "${it.first().uppercaseChar()}." }

    companion object {
        /** Accepts "First Middle Last" or "Last, First". */
        fun parse(raw: String): PersonName {
            val s = raw.trim().replace(Regex("\\s+"), " ")
            if (s.contains(',')) {
                val last = s.substringBefore(',').trim()
                val first = s.substringAfter(',').trim()
                return PersonName(first, last)
            }
            val parts = s.split(' ')
            if (parts.size == 1) return PersonName("", parts[0])
            return PersonName(parts.dropLast(1).joinToString(" "), parts.last())
        }
    }
}

/**
 * Formats bibliography entries and quote citations. Output is plain text; [italic] wraps the
 * title so Markdown or rich text callers can mark it up. Titles are kept as the user stored them
 * (APA's sentence-case rule is left to the user, because automatic case changes mangle proper nouns).
 */
object Citations {

    fun bibliography(source: CitationSource, style: CitationStyle, italic: (String) -> String = { it }): String {
        val names = source.authors.filter { it.isNotBlank() }.map(PersonName::parse)
        val title = italic(source.title.trim().trimEnd('.'))
        val year = source.year?.trim()?.take(4)?.takeIf { it.isNotEmpty() }
        val publisher = source.publisher?.trim()?.takeIf { it.isNotEmpty() }
        return when (style) {
            CitationStyle.APA -> buildString {
                if (names.isNotEmpty()) append(apaAuthors(names)).append(' ')
                append('(').append(year ?: "n.d.").append("). ")
                append(title).append('.')
                if (publisher != null) append(' ').append(publisher).append('.')
            }
            CitationStyle.MLA -> buildString {
                if (names.isNotEmpty()) append(mlaAuthors(names)).append(' ')
                append(title).append('.')
                val tail = listOfNotNull(publisher, year).joinToString(", ")
                if (tail.isNotEmpty()) append(' ').append(tail).append('.')
            }
            CitationStyle.CHICAGO -> buildString {
                if (names.isNotEmpty()) append(mlaAuthors(names)).append(' ')
                append(title).append('.')
                val pub = listOfNotNull(
                    listOfNotNull(source.place?.trim()?.takeIf { it.isNotEmpty() }, publisher).joinToString(": ").takeIf { it.isNotEmpty() },
                    year,
                ).joinToString(", ")
                if (pub.isNotEmpty()) append(' ').append(pub).append('.')
            }
        }.replace("..", ".")
    }

    /** Short in-text citation, e.g. "(Melville, 1851, p. 14)". [location] is a page number or a label such as "Chapter 1". */
    fun inText(source: CitationSource, style: CitationStyle, location: String?): String {
        val names = source.authors.filter { it.isNotBlank() }.map(PersonName::parse)
        val who = when {
            names.isEmpty() -> source.title.take(30)
            names.size == 1 -> names[0].last
            names.size == 2 -> if (style == CitationStyle.APA) "${names[0].last} & ${names[1].last}" else "${names[0].last} and ${names[1].last}"
            else -> "${names[0].last} et al."
        }
        val loc = location?.trim()?.takeIf { it.isNotEmpty() }
        val pageLike = loc?.all { it.isDigit() || it == '-' } == true
        return when (style) {
            CitationStyle.APA -> {
                val parts = mutableListOf(who, source.year?.take(4) ?: "n.d.")
                if (loc != null) parts += if (pageLike) "p. $loc" else loc
                "(" + parts.joinToString(", ") + ")"
            }
            CitationStyle.MLA, CitationStyle.CHICAGO -> if (loc != null) "($who ${if (pageLike) loc else loc})" else "($who)"
        }
    }

    fun quoteWithCitation(quote: String, source: CitationSource, style: CitationStyle, location: String?): String =
        "“${quote.trim()}” ${inText(source, style, location)}\n\n${bibliography(source, style)}"

    private fun apaAuthors(names: List<PersonName>): String {
        fun one(n: PersonName) = if (n.first.isEmpty()) n.last else "${n.last}, ${n.initials}"
        return when (names.size) {
            1 -> one(names[0])
            2 -> "${one(names[0])}, & ${one(names[1])}"
            in 3..20 -> names.dropLast(1).joinToString(", ") { one(it) } + ", & " + one(names.last())
            else -> names.take(19).joinToString(", ") { one(it) } + ", . . . " + one(names.last())
        }
    }

    private fun mlaAuthors(names: List<PersonName>): String {
        fun inverted(n: PersonName) = if (n.first.isEmpty()) n.last else "${n.last}, ${n.first}"
        fun natural(n: PersonName) = if (n.first.isEmpty()) n.last else "${n.first} ${n.last}"
        val text = when (names.size) {
            1 -> inverted(names[0])
            2 -> "${inverted(names[0])}, and ${natural(names[1])}"
            else -> "${inverted(names[0])}, et al"
        }
        return if (text.endsWith('.')) text else "$text."
    }
}
