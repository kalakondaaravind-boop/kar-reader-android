package com.kar.reader.desktop

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One row of the Action points table. */
@Serializable
data class ActionPoint(
    val text: String = "",
    val owner: String = "",
    val target: String = "",       // "12 Nov 2026" or empty for "–"
    val status: String = "Open",   // Open | Initiated | Closed
    val notes: String = "",
)

/** Minutes of a meeting, kept per transcript. Everything is editable; the draft is only a starting point. */
@Serializable
data class Minutes(
    val transcriptId: String,
    val meeting: String = "",
    val startedAt: Long = 0,
    val durationMs: Long = 0,
    val project: String = "",
    val attendees: List<String> = emptyList(),
    val agenda: List<String> = emptyList(),
    val discussion: String = "",
    val decisions: List<String> = emptyList(),
    val actions: List<ActionPoint> = emptyList(),
    val upper: Boolean = true,     // the sample minutes are in capitals
    val edited: Boolean = false,
)

object MinutesFormat {
    private val en = Locale.ENGLISH
    fun day(ms: Long): String = SimpleDateFormat("d MMM yyyy", en).format(Date(ms))
    fun dayTime(ms: Long): String = SimpleDateFormat("d MMM yyyy, hh:mm a", en).format(Date(ms))

    /** "2 h 30", "45 min" */
    fun duration(ms: Long): String {
        val mins = ((ms + 30_000) / 60_000).toInt().coerceAtLeast(1)
        return if (mins < 60) "$mins min" else "${mins / 60} h ${"%02d".format(mins % 60)}"
    }

    /** "11 Nov 2026, 04:00 PM (2 h 30)" */
    fun dateAndTime(m: Minutes): String = dayTime(m.startedAt) + (if (m.durationMs > 0) " (${duration(m.durationMs)})" else "")
}

/** Builds a first draft of the minutes from a transcript, using plain rules. There is no AI model: a person must review it. */
object MinutesBuilder {
    private class Sentence(val text: String, val speaker: Int, val ms: Long)

    private val stop = setOf(
        "the", "and", "that", "this", "with", "have", "for", "are", "was", "were", "will", "you", "your", "not", "but", "from", "they", "them", "then", "than", "there",
        "their", "what", "when", "which", "would", "could", "should", "about", "also", "just", "like", "into", "because", "been", "being", "some", "more", "very", "can",
        "had", "has", "its", "our", "out", "all", "any", "one", "yeah", "okay", "right", "know", "think", "going", "want", "need", "get", "got", "see", "say", "said",
        "let", "now", "here", "how", "who", "why", "yes", "well", "thing", "things", "kind", "sort", "actually", "basically", "really", "maybe", "much", "many", "over",
        "mein", "hai", "hain", "toh", "aur", "nahi", "kya", "yeh", "woh", "ka", "ki", "ke", "ko", "se", "par", "bhi", "ho", "tha", "thi",
    )
    private val decisionCue = Regex("""\b(decided|decision|agreed|agree|approved|approve|finali[sz]ed?|confirmed|conclude[ds]?|resolved|going forward|from now on|we will go with|let's go with|go ahead|signed off|sign off|will be (done|used|followed))\b""", RegexOption.IGNORE_CASE)
    private val actionCue = Regex("""\b(will|shall|going to|need to|needs to|have to|has to|must|should|please|kindly|action|follow[- ]?up|take up|let's|lets|can you|could you|i'll|we'll|you'll|karna hai|karenge|kar denge|bhej|dekhna hai)\b""", RegexOption.IGNORE_CASE)
    private val actionVerb = Regex("""\b(send|share|prepare|schedule|invite|complete|finish|update|review|check|call|connect|set up|arrange|submit|draft|revert|confirm|close|circulate|coordinate|finali[sz]e|create|build|document|plan|reach out|inform|bring|collect|provide|follow up|follow-up|discuss|align|organi[sz]e|track|raise|propose|publish|fix|test|deploy|book|get back|take forward|take up|action)\b""", RegexOption.IGNORE_CASE)
    private val announcesTopics = Regex("""\b(today we|we will discuss|we'll discuss|agenda for)\b""", RegexOption.IGNORE_CASE)
    private val notNames = setOf("We", "You", "They", "This", "That", "There", "Please", "Today", "It", "He", "She", "Then", "Also", "And", "But", "So", "Let", "Kindly", "Everyone", "Someone", "Team", "Who")
    private val agendaCue = Regex("""\b(agenda|topics?|today we|we will discuss|we'll discuss|discuss|talk about|go through|walk through|review of)\b""", RegexOption.IGNORE_CASE)

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val weekdays = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    fun draft(meta: TranscriptMeta, lines: List<Line>, settings: Settings): Minutes {
        val startedAt = meta.startedAt.takeIf { it > 0 } ?: meta.createdAt
        val usable = lines.filter { displayText(it.text, settings).isNotBlank() }
        val sentences = usable.flatMap { l ->
            displayText(l.text, settings).split(Regex("(?<=[.!?।])\\s+")).map { it.trim() }.filter { it.split(' ').size >= 3 }.map { Sentence(it, l.speaker, l.startMs) }
        }
        val attendees = usable.map { it.speaker }.distinct().sorted().map { speakerName(meta, it) }
        val tf = HashMap<String, Int>()
        sentences.forEach { s -> words(s.text).forEach { tf[it] = (tf[it] ?: 0) + 1 } }
        fun score(s: Sentence): Double {
            val w = words(s.text)
            if (w.isEmpty()) return 0.0
            return w.sumOf { (tf[it] ?: 0).toDouble() } / Math.pow(w.size.toDouble(), 0.6)
        }

        // Agenda: the best sentence from each of up to five time slices, with any sentence that announces topics preferred.
        val agenda = ArrayList<String>()
        if (sentences.isNotEmpty()) {
            val slices = minOf(5, maxOf(1, sentences.size / 4))
            val per = (sentences.size + slices - 1) / slices
            sentences.chunked(per).forEach { chunk ->
                val pick = chunk.filter { agendaCue.containsMatchIn(it.text) }.maxByOrNull { score(it) } ?: chunk.maxByOrNull { score(it) }
                pick?.let { agenda += shorten(it.text, 110) }
            }
        }

        // Discussion: the highest-scoring sentences, kept in the order they were spoken, within about 900 characters.
        val picked = HashSet<Sentence>()
        var budget = 900
        for (s in sentences.sortedByDescending { score(it) }) {
            if (s.text.length > budget) continue
            picked += s; budget -= s.text.length + 1
            if (budget < 60) break
        }
        val discussion = sentences.filter { it in picked }.joinToString(" ") { it.text }

        val decisionSentences = sentences.filter { decisionCue.containsMatchIn(it.text) }
        val decisions = decisionSentences.map { shorten(it.text, 220) }.distinct().take(10)

        val actionSentences = sentences.filter {
            actionCue.containsMatchIn(it.text) && actionVerb.containsMatchIn(it.text) && !it.text.trim().endsWith("?") &&
                it !in decisionSentences && !announcesTopics.containsMatchIn(it.text)
        }
            .distinctBy { it.text.lowercase() }.take(20)
        val actions = actionSentences.map { s ->
            ActionPoint(
                text = shorten(s.text, 220),
                owner = ownerFor(s, attendees, meta),
                target = parseTarget(s.text, startedAt),
                status = "Open",
                notes = "",
            )
        }
        return Minutes(
            transcriptId = meta.id, meeting = meta.title, startedAt = startedAt, durationMs = meta.durationMs,
            attendees = attendees, agenda = agenda, discussion = discussion, decisions = decisions, actions = actions,
        )
    }

    private fun words(s: String): List<String> = Regex("[\\p{L}]{4,}").findAll(s.lowercase()).map { it.value }.filter { it !in stop }.toList()

    private fun shorten(s: String, max: Int): String {
        val t = s.trim().trimEnd('.', ';', ',')
        if (t.length <= max) return t
        val cut = t.substring(0, max)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > max / 2 } ?: max).trimEnd(',', ' ') + "…"
    }

    private fun ownerFor(s: Sentence, attendees: List<String>, meta: TranscriptMeta): String {
        val named = attendees.filter { !it.startsWith("Speaker ") }
        // A real name spoken in the sentence wins ("Vishal will send the deck").
        val hit = named.mapNotNull { n ->
            val first = n.trim().split(' ').first()
            if (first.length < 2) null else Regex("\\b${Regex.escape(first)}\\b", RegexOption.IGNORE_CASE).find(s.text)?.let { it.range.first to n }
        }.minByOrNull { it.first }
        if (hit != null) return hit.second
        // "Aravind will prepare…" names someone who may not have spoken.
        Regex("""^([A-Z][a-z]{2,})\s+(will|shall|to|should|can|needs to|has to)\b""").find(s.text.trim())?.let { m ->
            if (m.groupValues[1] !in notNames) return m.groupValues[1]
        }
        return speakerName(meta, s.speaker)
    }

    /** Finds a due date written in words and returns it as "12 Nov 2026", or "" when there is none. */
    fun parseTarget(text: String, refMs: Long): String {
        val t = text.lowercase()
        val ref = Calendar.getInstance().apply { timeInMillis = refMs; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0) }
        fun fmt(c: Calendar) = MinutesFormat.day(c.timeInMillis)
        fun plus(days: Int) = (ref.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, days) }
        fun withYear(day: Int, month: Int, year: Int?): Calendar? {
            if (month !in 0..11 || day !in 1..31) return null
            val c = (ref.clone() as Calendar)
            c.isLenient = false
            return try {
                c.set(year ?: ref.get(Calendar.YEAR), month, day); c.time
                if (year == null && c.timeInMillis < ref.timeInMillis - 30L * 86_400_000) c.add(Calendar.YEAR, 1)
                c
            } catch (e: Exception) { null }
        }
        // 12 Nov, 12th November 2026
        Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s*(?:of\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?(?:,?\s+(\d{4}))?""").find(t)?.let { m ->
            withYear(m.groupValues[1].toInt(), months.indexOf(m.groupValues[2]), m.groupValues[3].toIntOrNull())?.let { return fmt(it) }
        }
        // Nov 12, November 12th 2026
        Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?""").find(t)?.let { m ->
            withYear(m.groupValues[2].toInt(), months.indexOf(m.groupValues[1]), m.groupValues[3].toIntOrNull())?.let { return fmt(it) }
        }
        // 12/11, 12-11-2026 (day first)
        Regex("""\b(?:by|on|before|till|until|due|from)\s+(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\b""").find(t)?.let { m ->
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            withYear(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, y)?.let { return fmt(it) }
        }
        if (Regex("""\bday after tomorrow\b""").containsMatchIn(t)) return fmt(plus(2))
        if (Regex("""\btomorrow\b""").containsMatchIn(t)) return fmt(plus(1))
        if (Regex("""\b(today|end of (the )?day|eod)\b""").containsMatchIn(t)) return fmt(plus(0))
        weekdays.forEachIndexed { i, name ->
            if (Regex("""\b$name\b""").containsMatchIn(t)) {
                var delta = (i + 1 - ref.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (delta == 0) delta = 7
                return fmt(plus(delta))
            }
        }
        if (Regex("""\bnext week\b""").containsMatchIn(t)) return fmt(plus(7))
        if (Regex("""\b(end of (the )?week|this week)\b""").containsMatchIn(t)) {
            val delta = (6 - ref.get(Calendar.DAY_OF_WEEK) + 7) % 7   // Friday
            return fmt(plus(delta))
        }
        if (Regex("""\b(end of (the )?month|month[- ]end)\b""").containsMatchIn(t)) {
            val c = ref.clone() as Calendar
            c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH))
            return fmt(c)
        }
        return ""
    }
}

object MinutesExport {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    private fun statusColour(a: ActionPoint, meetingStart: Long, todayMs: Long): String = when {
        a.status.equals("Closed", true) -> "#C6E0B4"
        a.status.equals("Initiated", true) -> "#BDD7EE"
        a.target.isNotBlank() && a.target == MinutesFormat.day(todayMs) -> "#FFE699"
        else -> "#EDEDED"
    }

    private fun t(m: Minutes, s: String) = if (m.upper) s.uppercase() else s

    /** The same minutes as the sample: a greeting, a details table, agenda, discussion, key decisions, the action table and a colour key. */
    fun html(m: Minutes, todayMs: Long = System.currentTimeMillis(), forWord: Boolean = false): String {
        val cell = "border:1px solid #bfbfbf;padding:6px 9px;vertical-align:top;font-size:10.5pt;"
        val head = "$cell background:#1f4e9e;color:#ffffff;font-weight:bold;text-align:left;"
        fun row(k: String, v: String) = "<tr><td style=\"${cell}font-weight:bold;background:#eef3fb;width:150px\">${esc(k)}</td><td style=\"$cell\">${esc(v)}</td></tr>"
        fun numbered(items: List<String>, semicolons: Boolean) = if (items.isEmpty()) "<p style=\"margin:4px 0\">–</p>" else
            items.mapIndexed { i, x -> "<p style=\"margin:3px 0\">${i + 1}. ${esc(t(m, x))}${if (semicolons && i < items.lastIndex) ";" else if (semicolons) "." else ""}</p>" }.joinToString("")
        val intro = "Hi All,<br><br>Please find below the minutes of the meeting held on ${esc(MinutesFormat.day(m.startedAt))}. Request you to close the action points by the target dates mentioned."
        val body = buildString {
            append("<div style=\"font-family:Calibri,Arial,sans-serif;font-size:11pt;color:#1a1a1a\">")
            append("<p>$intro</p>")
            append("<table style=\"border-collapse:collapse;width:100%;max-width:880px\"><tr><th style=\"$head width:150px\">Particulars</th><th style=\"$head\">Details</th></tr>")
            append(row("Meeting", t(m, m.meeting)))
            append(row("Date & time", MinutesFormat.dateAndTime(m)))
            append(row("Project", t(m, m.project)))
            append(row("Attendees", t(m, m.attendees.joinToString(", "))))
            append("</table>")
            append("<h3 style=\"color:#1f4e9e;margin:18px 0 4px\">Agenda</h3>").append(numbered(m.agenda, true))
            append("<h3 style=\"color:#1f4e9e;margin:18px 0 4px\">Discussion</h3><p style=\"margin:4px 0\">${if (m.discussion.isBlank()) "–" else esc(t(m, m.discussion))}</p>")
            append("<h3 style=\"color:#1f4e9e;margin:18px 0 4px\">Key decisions</h3>").append(numbered(m.decisions, false))
            append("<h3 style=\"color:#1f4e9e;margin:18px 0 4px\">Action points</h3>")
            append("<table style=\"border-collapse:collapse;width:100%\"><tr>")
            listOf("#", "Action point", "Responsibility", "Target date", "Status", "Notes").forEach { append("<th style=\"$head\">$it</th>") }
            append("</tr>")
            if (m.actions.isEmpty()) append("<tr><td style=\"$cell\" colspan=\"6\">No action points were recorded.</td></tr>")
            m.actions.forEachIndexed { i, a ->
                val colour = statusColour(a, m.startedAt, todayMs)
                append("<tr><td style=\"$cell\">${i + 1}</td><td style=\"$cell\">${esc(t(m, a.text))}</td><td style=\"$cell\">${esc(t(m, a.owner))}</td>")
                append("<td style=\"$cell\">${esc(a.target.ifBlank { "–" })}</td><td style=\"$cell background:$colour;font-weight:bold\">${esc(a.status)}</td><td style=\"$cell\">${esc(t(m, a.notes))}</td></tr>")
            }
            append("</table>")
            append("<p style=\"margin-top:14px;font-size:9.5pt\"><b>Colour key:</b> ")
            listOf("Due today" to "#FFE699", "Initiated" to "#BDD7EE", "Open" to "#EDEDED", "Complete" to "#C6E0B4").forEach { (n, c) ->
                append("<span style=\"background:$c;padding:2px 9px;border:1px solid #bfbfbf;margin-right:6px\">$n</span>")
            }
            append("</p></div>")
        }
        return if (!forWord) body else
            "<html xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:w=\"urn:schemas-microsoft-com:office:word\" xmlns=\"http://www.w3.org/TR/REC-html40\">" +
                "<head><meta charset=\"utf-8\"><title>${esc(m.meeting)}</title><!--[if gte mso 9]><xml><w:WordDocument><w:View>Print</w:View></w:WordDocument></xml><![endif]--></head><body>$body</body></html>"
    }

    fun page(m: Minutes): String = "<!doctype html><html><head><meta charset=\"utf-8\"><title>${esc(m.meeting)}</title></head><body style=\"margin:24px\">${html(m)}</body></html>"

    fun text(m: Minutes): String = buildString {
        appendLine("Hi All,")
        appendLine()
        appendLine("Please find below the minutes of the meeting held on ${MinutesFormat.day(m.startedAt)}. Request you to close the action points by the target dates mentioned.")
        appendLine()
        appendLine("Meeting:      ${t(m, m.meeting)}")
        appendLine("Date & time:  ${MinutesFormat.dateAndTime(m)}")
        appendLine("Project:      ${t(m, m.project)}")
        appendLine("Attendees:    ${t(m, m.attendees.joinToString(", "))}")
        appendLine()
        appendLine("Agenda")
        m.agenda.forEachIndexed { i, x -> appendLine("${i + 1}. ${t(m, x)}${if (i < m.agenda.lastIndex) ";" else "."}") }
        appendLine()
        appendLine("Discussion")
        appendLine(t(m, m.discussion).ifBlank { "–" })
        appendLine()
        appendLine("Key decisions")
        m.decisions.forEachIndexed { i, x -> appendLine("${i + 1}. ${t(m, x)}") }
        appendLine()
        appendLine("Action points")
        appendLine("#  | Action point | Responsibility | Target date | Status | Notes")
        m.actions.forEachIndexed { i, a -> appendLine("${i + 1} | ${t(m, a.text)} | ${t(m, a.owner)} | ${a.target.ifBlank { "–" }} | ${a.status} | ${t(m, a.notes)}") }
        appendLine()
        appendLine("Colour key: Due today / Initiated / Open / Complete")
    }
}
