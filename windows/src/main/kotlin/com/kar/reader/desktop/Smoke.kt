package com.kar.reader.desktop

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import com.kar.reader.core.domain.TranscriptText
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * Headless checks run by CI (not part of the app UI):
 *   books                      import, render and read a generated PDF and EPUB
 *   seed                       fill the data folder with demo books, highlights, notes and cards (for screenshots)
 *   speech <audio> [language] [expected speakers] [expect-devanagari]   run the real speech pipeline
 */
fun main(args: Array<String>) {
    try {
        when (args.firstOrNull()) {
            "books" -> { books(); println("BOOKS OK") }
            "seed" -> { seed(); println("SEED OK") }
            "speech" -> speech(args.drop(1))
            "minutes" -> { minutesCheck(); println("MINUTES OK") }
            "mix" -> { mixCheck(); println("MIX OK") }
            else -> { System.err.println("usage: books | seed | speech <audio> …"); exitProcess(2) }
        }
        exitProcess(0)
    } catch (t: Throwable) {
        t.printStackTrace()
        println("SMOKE FAILED: ${t.message}")
        exitProcess(1)
    }
}

private val lorem = listOf(
    "Reading is a conversation between a mind and a page.",
    "Highlight what surprises you, then ask why it did.",
    "A good note says what the passage means to you, not what it says.",
    "Spaced repetition brings an idea back just before it fades.",
)

fun makePdf(file: File, title: String, pages: Int) {
    PDDocument().use { doc ->
        doc.documentInformation.title = title
        doc.documentInformation.author = "Asha Rao"
        repeat(pages) { p ->
            val page = PDPage(); doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 22f); cs.newLineAtOffset(72f, 700f)
                cs.showText("$title - page ${p + 1}"); cs.endText()
                var y = 660f
                lorem.forEach { line ->
                    cs.beginText(); cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 13f); cs.newLineAtOffset(72f, y); cs.showText(line); cs.endText(); y -= 22f
                }
            }
        }
        doc.save(file)
    }
}

fun makeEpub(file: File, title: String) {
    ZipOutputStream(file.outputStream()).use { z ->
        fun add(name: String, text: String, stored: Boolean = false) {
            val e = ZipEntry(name)
            if (stored) { val b = text.toByteArray(); e.method = ZipEntry.STORED; e.size = b.size.toLong(); e.compressedSize = b.size.toLong(); e.crc = java.util.zip.CRC32().also { it.update(b) }.value }
            z.putNextEntry(e); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry()
        }
        add("mimetype", "application/epub+zip", stored = true)
        add("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
        add("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">kar-demo</dc:identifier><dc:title>$title</dc:title><dc:creator>Dev Malhotra</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""")
        add("OEBPS/nav.xhtml", """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="c1.xhtml">The quiet hour</a></li><li><a href="c2.xhtml">Deep attention</a></li></ol></nav></body></html>""")
        add("OEBPS/c1.xhtml", """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><body><h1>The quiet hour</h1><p>Every evening the house goes still, and the book on the table becomes the loudest thing in the room &amp; the most welcome.</p><p>${lorem.joinToString(" ")}</p><p>Caf&#233; notes: &ldquo;attention is a muscle&rdquo;.</p></body></html>""")
        add("OEBPS/c2.xhtml", """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Deep attention</h1><p>${lorem.reversed().joinToString(" ")}</p><p>${lorem.joinToString(" ")}</p></body></html>""")
    }
}

private fun books() {
    val tmp = File(Paths.root, "smoke-input").also { it.mkdirs() }
    val pdf = File(tmp, "sample.pdf").also { makePdf(it, "KAR READER sample", 3) }
    val epub = File(tmp, "sample.epub").also { makeEpub(it, "Sample Epub") }
    val state = AppState()
    val result = state.importFiles(listOf(pdf, epub))
    check(result.added.size == 2) { "import failed: ${result.skipped}" }
    val again = state.importFiles(listOf(pdf))
    check(again.added.isEmpty() && again.skipped.isNotEmpty()) { "duplicate was imported" }
    val p = result.added.first { it.isPdf }
    val e = result.added.first { !it.isPdf }
    check(p.title == "KAR READER sample" && p.units == 3) { "pdf meta wrong: ${p.title} ${p.units}" }
    check(e.title == "Sample Epub" && e.authors == listOf("Dev Malhotra") && e.units == 2) { "epub meta wrong: ${e.title} ${e.authors} ${e.units}" }
    PdfBook(File(Paths.books, p.file)).use { doc ->
        check(doc.pageCount == 3)
        val img = doc.render(0, 1.2f)
        check(img.width > 300 && img.height > 300) { "render too small" }
        check(doc.text(1).contains("page 2")) { "text layer: ${doc.text(1)}" }
        val region = doc.textInArea(0, 0.05f, 0.05f, 0.9f, 0.2f)
        check(region.contains("KAR READER")) { "area text was '$region'" }
        check(snippetAround(doc.text(0), "surprises") != null)
    }
    EpubBook(File(Paths.books, e.file)).use { doc ->
        check(doc.chapters.size == 2 && doc.chapters[0].title == "The quiet hour") { "chapters ${doc.chapters.map { it.title }}" }
        val t = doc.text(0)
        check(t.contains("loudest thing in the room & the most welcome")) { "entities: $t" }
        check(t.contains("Café") && t.contains("“attention is a muscle”")) { "numeric entities: $t" }
        check(!t.contains("<")) { "tags left: $t" }
    }
    state.update { it.copy(highlights = it.highlights + Highlight("h1", e.id, text = "x", label = "c", unit = 0, start = 0, end = 1, createdAt = 1)) }
    check(AppState().data.highlights.size == 1) { "state didn't persist" }
    println("Data folder: ${Paths.root}")
}

private val demoMeetingLines = listOf(
    Triple(0, 0L, "Today we will discuss the NPS survey rollout and the wallet share numbers for the quarter."),
    Triple(0, 22_000L, "We agreed to develop a human plus AI survey offering for our key accounts."),
    Triple(1, 41_000L, "Please invite the first line CBHs to the next meeting tomorrow."),
    Triple(1, 63_000L, "I will share the Titan CSAT summary by Friday."),
    Triple(0, 88_000L, "Aravind will prepare the proposal for the healthcare aggregator model by 16 Nov."),
    Triple(1, 110_000L, "The Razorpay recovery plan needs a review next week, and we decided to include first line CBHs in the following meetings."),
)

private fun demoMeetingMeta(id: String, startMs: Long) =
    TranscriptMeta(id, "Wallet share discussion", startMs + 150 * 60_000L, 150 * 60_000L, "$id.wav", "", "English", mapOf(0 to "Vishal Gurung", 1 to "Venky"), status = "done", startedAt = startMs, meeting = true)

private fun meetingStart(): Long = java.util.Calendar.getInstance().apply { clear(); set(2026, java.util.Calendar.NOVEMBER, 11, 16, 0) }.timeInMillis

private fun minutesCheck() {
    val start = meetingStart()
    val meta = demoMeetingMeta("minutes-check", start)
    val lines = demoMeetingLines.map { (sp, at, text) -> Line(at, at + 15_000, sp, text) }
    val m = MinutesBuilder.draft(meta, lines, Settings())
    println("Meeting: ${m.meeting} · ${MinutesFormat.dateAndTime(m)} · attendees ${m.attendees}")
    println("Agenda: ${m.agenda}")
    println("Decisions: ${m.decisions}")
    m.actions.forEach { println("Action: ${it.text} | ${it.owner} | ${it.target.ifBlank { "–" }} | ${it.status}") }
    check(MinutesFormat.dateAndTime(m) == "11 Nov 2026, 04:00 PM (2 h 30)") { "date line was ${MinutesFormat.dateAndTime(m)}" }
    check(m.attendees == listOf("Vishal Gurung", "Venky")) { "attendees were ${m.attendees}" }
    check(m.agenda.isNotEmpty()) { "no agenda" }
    check(m.decisions.any { it.contains("agreed", true) }) { "decision not found" }
    val aravind = m.actions.firstOrNull { it.owner == "Aravind" || it.text.contains("healthcare") }
    check(aravind != null && aravind.target == "16 Nov 2026") { "Aravind's action/date wrong: $aravind" }
    check(m.actions.any { it.target == "12 Nov 2026" }) { "'tomorrow' was not turned into 12 Nov 2026" }
    check(m.actions.any { it.target == "13 Nov 2026" }) { "'Friday' was not turned into 13 Nov 2026" }
    check(MinutesBuilder.parseTarget("by 3rd December", start) == "3 Dec 2026")
    check(MinutesBuilder.parseTarget("before 14/11", start) == "14 Nov 2026")
    check(MinutesBuilder.parseTarget("nothing here", start) == "")
    val html = MinutesExport.html(m)
    for (needed in listOf("Hi All,", "minutes of the meeting held on 11 Nov 2026", "Particulars", "Attendees", "Agenda", "Discussion", "Key decisions", "Action points", "Responsibility", "Target date", "Colour key")) {
        check(html.contains(needed)) { "export is missing “$needed”" }
    }
    check(MinutesExport.html(m, forWord = true).startsWith("<html")) { "word export malformed" }
    check(MinutesExport.text(m).contains("Action points")) { "text export malformed" }
    val out = File(Paths.root, "minutes-check.html").also { it.writeText(MinutesExport.page(m), Charsets.UTF_8) }
    println("Wrote ${out.absolutePath}")
}

private fun mixCheck() {
    val dir = File(Paths.root, "mix-check").also { it.mkdirs() }
    fun tone(f: File, hz: Double, seconds: Int, amp: Float) = WavWriter(f).use { w ->
        w.writeFloats(FloatArray(SPEECH_RATE * seconds) { (Math.sin(2 * Math.PI * hz * it / SPEECH_RATE) * amp).toFloat() })
    }
    val a = File(dir, "a.wav").also { tone(it, 220.0, 5, 0.4f) }
    val b = File(dir, "b.wav").also { tone(it, 440.0, 7, 0.4f) }
    val out = File(dir, "mix.wav")
    MeetingMix.mix(a, b, out)
    WavReader(out).use { r ->
        println("Mixed length ${r.durationSec} s")
        check(kotlin.math.abs(r.durationSec - 7f) < 0.1f) { "mixed length should follow the longer file" }
        val early = r.read(1f, 2f).maxOf { kotlin.math.abs(it) }
        val late = r.read(6f, 7f).maxOf { kotlin.math.abs(it) }
        check(early > 0.5f) { "both voices should be present early on ($early)" }
        check(late in 0.3f..0.4f) { "only the second voice should remain later ($late)" }
    }
    val solo = File(dir, "solo.wav"); MeetingMix.mix(a, null, solo)
    check(solo.length() == a.length()) { "no system audio should copy the microphone file" }
}

private fun seedMeeting(state: AppState) {
    val start = meetingStart()
    val id = "demo-meeting"
    val meta = demoMeetingMeta(id, start)
    TranscriptFiles.save(id, demoMeetingLines.map { (sp, at, text) -> Line(at, at + 15_000, sp, text) })
    val minutes = MinutesBuilder.draft(meta, TranscriptFiles.load(id), Settings()).copy(project = "Management reporting")
    state.update { it.copy(transcripts = it.transcripts + meta, minutes = it.minutes + minutes) }
}

private fun seed() {
    val tmp = File(Paths.root, "seed-input").also { it.mkdirs() }
    val state = AppState()
    val r = state.importFiles(listOf(
        File(tmp, "The Reading Habit.pdf").also { makePdf(it, "The Reading Habit", 12) },
        File(tmp, "A Field Guide to Focus.epub").also { makeEpub(it, "A Field Guide to Focus") },
        File(tmp, "Notes on Attention.pdf").also { makePdf(it, "Notes on Attention", 6) },
    ))
    val now = System.currentTimeMillis()
    val pdf = r.added.firstOrNull { it.title == "The Reading Habit" } ?: return
    val epub = r.added.firstOrNull { !it.isPdf }
    state.update { d ->
        d.copy(
            highlights = d.highlights + listOfNotNull(
                Highlight("d1", pdf.id, "YELLOW", lorem[1], "Try this tonight.", "p. 1", 0, rx = 0.1f, ry = 0.16f, rw = 0.7f, rh = 0.04f, createdAt = now - 3_600_000),
                Highlight("d2", pdf.id, "BLUE", lorem[3], "", "p. 2", 1, rx = 0.1f, ry = 0.22f, rw = 0.7f, rh = 0.04f, createdAt = now - 1_800_000),
                epub?.let { Highlight("d3", it.id, "GREEN", "attention is a muscle", "Quote for the essay.", "The quiet hour", 0, 0, 10, createdAt = now - 600_000) },
            ),
            notes = d.notes + Note("n1", pdf.id, "Reading plan", "Twenty pages before bed.\nRevisit highlights on Sunday.", now),
            cards = d.cards + Card("c1", pdf.id, lorem[2], "Say what it means to you.", now - 1000) + Card("c2", pdf.id, "What does spaced repetition do?", "Brings an idea back just before it fades.", now - 500),
            sessions = d.sessions + (0..9).filter { it % 3 != 2 }.map { Session(pdf.id, now - it * 86_400_000L, (12 + it * 3) * 60_000L) },
            books = d.books.map { if (it.id == pdf.id) it.copy(status = "reading", lastOpenedAt = now, position = 1, units = 12) else it },
        )
    }
    seedMeeting(state)
}

private fun speech(a: List<String>) {
    val audio = File(a[0])
    val language = a.getOrNull(1)?.takeIf { it != "auto" } ?: ""
    val expectSpeakers = a.getOrNull(2)?.toIntOrNull() ?: 0
    val expectDeva = a.contains("expect-devanagari")
    val id = java.util.UUID.randomUUID().toString()
    val wav = File(Paths.recordings, "$id.wav")
    val ms = AudioImport.toSpeechWav(audio, wav, {}, { false })
    println("Imported ${audio.name}: ${ms / 1000.0} s")
    val t0 = System.currentTimeMillis()
    val r = SpeechEngine().transcribe(wav, true, 0, language, { s, p -> if (p >= 1f) println("  $s done") }, { false })
    println("Transcribed in ${(System.currentTimeMillis() - t0) / 1000.0} s: ${r.speakerCount} speaker(s), languages: ${r.languages}")
    r.lines.forEach { println("  [${TranscriptText.clock(it.startMs)}] S${it.speaker + 1}: ${it.text}") }
    check(r.lines.isNotEmpty()) { "no speech found" }
    if (expectSpeakers > 0) check(r.speakerCount >= expectSpeakers) { "expected at least $expectSpeakers speakers, found ${r.speakerCount}" }
    if (expectDeva) check(r.lines.any { l -> l.text.any { it in 'ऀ'..'ॿ' } }) { "expected Devanagari text" }
    val state = AppState()
    val meta = TranscriptMeta(id, audio.nameWithoutExtension, System.currentTimeMillis(), ms, "$id.wav", language, r.languages, status = "done")
    state.update { it.copy(transcripts = it.transcripts + meta) }
    TranscriptFiles.save(id, r.lines.map { Line(it.startMs, it.endMs, it.speaker, it.text) })
    println("SPEECH OK")
}
