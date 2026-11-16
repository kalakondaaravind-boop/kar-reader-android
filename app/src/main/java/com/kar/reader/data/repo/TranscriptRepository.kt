package com.kar.reader.data.repo

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.kar.reader.core.domain.TranscriptLine
import com.kar.reader.core.domain.TranscriptText
import com.kar.reader.core.domain.WhisperLanguages
import com.kar.reader.data.db.BookDao
import com.kar.reader.data.db.NoteEntity
import com.kar.reader.data.db.TranscriptDao
import com.kar.reader.data.db.TranscriptEntity
import com.kar.reader.data.db.TranscriptMarkEntity
import com.kar.reader.data.db.TranscriptSegmentEntity
import com.kar.reader.data.db.TranscriptSpeakerEntity
import com.kar.reader.data.db.TranscriptStatus
import com.kar.reader.data.db.displayTitle
import com.kar.reader.data.files.DocumentStore
import com.kar.reader.feature.transcribe.TranscriptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

enum class TranscriptExport(val label: String, val extension: String, val mime: String) {
    TEXT("Plain text", "txt", "text/plain"),
    MARKDOWN("Markdown", "md", "text/markdown"),
    SRT("Subtitles (SRT)", "srt", "application/x-subrip"),
    PDF("PDF", "pdf", "application/pdf"),
}

@Singleton
class TranscriptRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TranscriptDao,
    private val bookDao: BookDao,
    private val store: DocumentStore,
    private val annotations: AnnotationRepository,
    private val manager: TranscriptionManager,
) {
    fun observeList(profileId: String): Flow<List<TranscriptEntity>> = dao.observeForProfile(profileId)
    fun observe(id: String): Flow<TranscriptEntity?> = dao.observe(id)
    fun observeSegments(id: String): Flow<List<TranscriptSegmentEntity>> = dao.observeSegments(id)
    fun observeSpeakers(id: String): Flow<List<TranscriptSpeakerEntity>> = dao.observeSpeakers(id)
    fun observeMarks(id: String): Flow<List<TranscriptMarkEntity>> = dao.observeMarks(id)
    fun observeDeleted(profileId: String): Flow<List<TranscriptEntity>> = dao.observeDeleted(profileId)

    fun audioFile(t: TranscriptEntity) = store.recordingFile(t.audioFile)

    suspend fun rename(id: String, title: String) = dao.rename(id, title.trim().ifEmpty { "Untitled recording" }.take(120), System.currentTimeMillis())

    suspend fun renameSpeaker(id: String, speaker: Int, name: String) =
        dao.upsertSpeaker(TranscriptSpeakerEntity(id, speaker, name.trim().ifEmpty { "Speaker ${speaker + 1}" }.take(40)))

    suspend fun mergeSpeakers(id: String, from: Int, into: Int) {
        // Merge the higher number into the lower so the remaining numbers stay contiguous.
        val (keep, drop) = if (from < into) from to into else into to from
        val keepName = dao.speakers(id).firstOrNull { it.speaker == into }?.name
        dao.mergeSpeakers(id, drop, keep, System.currentTimeMillis())
        if (keepName != null) dao.upsertSpeaker(TranscriptSpeakerEntity(id, keep, keepName))
    }

    suspend fun setSegmentSpeaker(segmentId: String, speaker: Int) = dao.setSegmentSpeaker(segmentId, speaker)

    suspend fun editSegment(segmentId: String, text: String) = dao.editSegment(segmentId, text.trim())

    /** Moves a transcript (and its recording) to the recycle bin; any running work on it stops. */
    suspend fun delete(id: String) {
        manager.cancel(id)
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) = dao.restore(id, System.currentTimeMillis())

    /** Deletes transcripts that left the bin, with their recordings. */
    suspend fun purge(cutoff: Long): Int = withContext(Dispatchers.IO) {
        val gone = dao.getDeletedBefore(cutoff)
        gone.forEach { t ->
            store.recordingFile(t.audioFile).delete()
            dao.deleteForever(t.id)
        }
        // Recordings left behind by a failed import or an interrupted delete.
        val known = dao.allAudioFiles().toSet()
        store.recordingsDir.listFiles().orEmpty().filter { it.name !in known && System.currentTimeMillis() - it.lastModified() > 86_400_000L }.forEach { it.delete() }
        gone.size
    }

    data class Loaded(val transcript: TranscriptEntity, val lines: List<TranscriptLine>, val names: Map<Int, String>, val bookTitle: String?)

    suspend fun load(id: String): Loaded {
        val t = dao.get(id) ?: throw IOException("Transcript not found")
        val lines = dao.segments(id).map { TranscriptLine(it.startMs, it.endMs, it.speaker, it.text) }
        val names = dao.speakers(id).associate { it.speaker to it.name }
        val book = t.bookId?.let { bookDao.get(it) }?.displayTitle
        return Loaded(t, lines, names, book)
    }

    private fun Loaded.name(speaker: Int) = names[speaker] ?: "Speaker ${speaker + 1}"

    private fun Loaded.subtitle(): String = listOfNotNull(
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(transcript.createdAt)),
        TranscriptText.clock(transcript.durationMs),
        transcript.detectedLanguages.takeIf { it.isNotBlank() }?.let { WhisperLanguages.describe(it) },
        bookTitle?.let { "linked to $it" },
        "transcribed on device",
    ).joinToString(" · ")

    suspend fun export(id: String, format: TranscriptExport, destination: Uri) = withContext(Dispatchers.IO) {
        val l = load(id)
        val out = context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("Can't write to that location")
        out.use { stream ->
            when (format) {
                TranscriptExport.TEXT -> stream.write(TranscriptText.plainText(l.transcript.title + "\n" + l.subtitle(), l.lines) { l.name(it) }.toByteArray())
                TranscriptExport.MARKDOWN -> stream.write(TranscriptText.markdown(l.transcript.title, l.subtitle(), l.lines) { l.name(it) }.toByteArray())
                TranscriptExport.SRT -> stream.write(TranscriptText.srt(l.lines) { l.name(it) }.toByteArray())
                TranscriptExport.PDF -> writePdf(l, stream)
            }
        }
    }

    /** Saves the transcript as a note (linked to the book, if any). The note is independent: editing one leaves the other. */
    suspend fun saveAsNote(profileId: String, id: String): NoteEntity {
        val l = load(id)
        val body = buildString {
            appendLine(l.subtitle())
            appendLine()
            TranscriptText.paragraphs(l.lines).forEach { p -> appendLine("${l.name(p.speaker)} (${TranscriptText.clock(p.startMs)}): ${p.text}"); appendLine() }
        }.trimEnd()
        return annotations.saveNote(profileId, null, "Transcript: ${l.transcript.title}", body, bookId = l.transcript.bookId)
    }

    fun isWorking(t: TranscriptEntity) = t.status in TranscriptStatus.working

    private fun writePdf(l: Loaded, stream: java.io.OutputStream) {
        val pageW = 595; val pageH = 842; val margin = 56; val contentW = pageW - margin * 2
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); color = 0xFF111111.toInt() }
        val metaP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = 0xFF555555.toInt() }
        val nameP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; typeface = Typeface.DEFAULT_BOLD; color = 0xFF1F3A5F.toInt() }
        val bodyP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; typeface = Typeface.SERIF; color = 0xFF1A1A1A.toInt() }
        val footP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = 0xFF888888.toInt() }
        fun layout(text: String, paint: TextPaint, width: Int) = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.25f).setIncludePad(false).build()
        fun finishPage() { page?.let { it.canvas.drawText("${l.transcript.title} — transcript · page $pageNo", margin.toFloat(), pageH - 28f, footP); doc.finishPage(it) } }
        fun newPage() { finishPage(); pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create()); y = margin.toFloat() }
        fun draw(text: String, paint: TextPaint, after: Float) {
            val lay = layout(text, paint, contentW)
            if (page == null || y + lay.height > pageH - margin) newPage()
            val c = page!!.canvas
            c.save(); c.translate(margin.toFloat(), y); lay.draw(c); c.restore()
            y += lay.height + after
        }
        newPage()
        draw(l.transcript.title, titleP, 4f)
        draw(l.subtitle(), metaP, 16f)
        TranscriptText.paragraphs(l.lines).forEach { p ->
            draw("${l.name(p.speaker)}  ·  ${TranscriptText.clock(p.startMs)}", nameP, 2f)
            draw(p.text, bodyP, 10f)
        }
        if (l.lines.isEmpty()) draw("No speech was recognized in this recording.", bodyP, 0f)
        finishPage()
        doc.writeTo(stream)
        doc.close()
    }
}
