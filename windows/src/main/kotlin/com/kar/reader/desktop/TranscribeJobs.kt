package com.kar.reader.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kar.reader.core.domain.HindiRoman
import com.kar.reader.core.domain.TranscriptLine
import com.kar.reader.core.domain.TranscriptText
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object TranscriptFiles {
    private fun file(id: String) = File(Paths.transcripts, "$id.json")
    fun load(id: String): List<Line> = runCatching { AppJson.decodeFromString<List<Line>>(file(id).readText()) }.getOrDefault(emptyList())
    fun save(id: String, lines: List<Line>) = file(id).writeText(AppJson.encodeToString(lines))
    fun deleteForever(state: AppState, t: TranscriptMeta) {
        state.update { d -> d.copy(transcripts = d.transcripts.filter { it.id != t.id }) }
        file(t.id).delete()
        if (t.audio.isNotBlank()) File(Paths.recordings, t.audio).delete()
    }
}

object FillerWords {
    private val filler = Regex("(?i)\\b(?:um+|uh+|uhm+|erm+|er|ah+|hmm+|mm+)\\b[,.]?\\s*")
    fun strip(text: String): String {
        if (!text.any { it in 'A'..'z' }) return text
        val s = filler.replace(text, "").replace(Regex("\\s{2,}"), " ").trim()
        return if (s.isNotEmpty() && text.firstOrNull()?.isUpperCase() == true) s.replaceFirstChar { it.uppercaseChar() } else s
    }
}

/** What a transcript line looks like on screen and in exports, given the user's settings. */
fun displayText(text: String, s: Settings): String {
    var t = text
    if (s.hindiRoman) t = HindiRoman.romanize(t)
    if (s.removeFillers) t = FillerWords.strip(t)
    return t
}

fun speakerName(meta: TranscriptMeta, speaker: Int) = meta.speakerNames[speaker]?.takeIf { it.isNotBlank() } ?: "Speaker ${speaker + 1}"

object ExportFormats {
    fun lines(meta: TranscriptMeta, lines: List<Line>, s: Settings): List<TranscriptLine> =
        lines.map { TranscriptLine(it.startMs, it.endMs, it.speaker, displayText(it.text, s)) }.filter { it.text.isNotBlank() }

    fun txt(meta: TranscriptMeta, l: List<Line>, s: Settings) = TranscriptText.plainText(meta.title, lines(meta, l, s)) { speakerName(meta, it) }
    fun md(meta: TranscriptMeta, l: List<Line>, s: Settings) =
        TranscriptText.markdown(meta.title, "${formatDate(meta.createdAt)} · ${meta.languages}".trim(' ', '·'), lines(meta, l, s)) { speakerName(meta, it) }
    fun srt(meta: TranscriptMeta, l: List<Line>, s: Settings) = TranscriptText.srt(lines(meta, l, s)) { speakerName(meta, it) }

    private fun vttTime(ms: Long) = "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    fun vtt(meta: TranscriptMeta, l: List<Line>, s: Settings) = buildString {
        append("WEBVTT\n\n")
        lines(meta, l, s).forEach { append(vttTime(it.startMs)).append(" --> ").append(vttTime(it.endMs)).append('\n').append("<v ${speakerName(meta, it.speaker)}>").append(it.text).append("\n\n") }
    }
    fun csv(meta: TranscriptMeta, l: List<Line>, s: Settings) = buildString {
        fun q(x: String) = "\"" + x.replace("\"", "\"\"") + "\""
        append("start_ms,end_ms,speaker,text\n")
        lines(meta, l, s).forEach { append("${it.startMs},${it.endMs},${q(speakerName(meta, it.speaker))},${q(it.text)}\n") }
    }
    fun json(meta: TranscriptMeta, l: List<Line>, s: Settings): String {
        val shown = lines(meta, l, s).map { Line(it.startMs, it.endMs, it.speaker, it.text) }
        return AppJson.encodeToString(ExportDoc(meta.title, meta.languages, meta.speakerNames.mapKeys { it.key.toString() }, shown))
    }
}

@kotlinx.serialization.Serializable
data class ExportDoc(val title: String, val languages: String, val speakers: Map<String, String>, val lines: List<Line>)

/** Runs transcriptions one at a time in the background. Everything happens on this computer. */
class TranscribeJobs(private val state: AppState) {
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "transcribe").apply { isDaemon = true } }
    private val flags = ConcurrentHashMap<String, AtomicBoolean>()
    private val pending = AtomicInteger(0)

    var busy by mutableStateOf(false)
    var activeId by mutableStateOf<String?>(null)
    var stage by mutableStateOf(Stage.SPEAKERS)
    var progress by mutableFloatStateOf(0f)

    init {
        // Anything left over from a previous run was interrupted. Say so, so it can be started again.
        state.update { d ->
            d.copy(transcripts = d.transcripts.map {
                if (it.status == "working" || it.status == "queued") it.copy(status = "failed", error = "Stopped when the app closed. Press “Transcribe again”.") else it
            })
        }
    }

    private fun setMeta(id: String, change: (TranscriptMeta) -> TranscriptMeta) =
        state.update { d -> d.copy(transcripts = d.transcripts.map { if (it.id == id) change(it) else it }) }

    fun submit(meta: TranscriptMeta) {
        if (state.data.transcripts.none { it.id == meta.id }) state.update { it.copy(transcripts = it.transcripts + meta) }
        setMeta(meta.id) { it.copy(status = "queued", error = "") }
        val flag = AtomicBoolean(false)
        flags[meta.id] = flag
        pending.incrementAndGet()
        busy = true
        pool.execute { run(meta, flag) }
    }

    fun cancel(id: String) { flags[id]?.set(true) }
    fun cancelAll() { flags.values.forEach { it.set(true) } }

    private fun run(meta: TranscriptMeta, flag: AtomicBoolean) {
        try {
            if (flag.get()) throw CancellationException()
            setMeta(meta.id) { it.copy(status = "working", error = "") }
            activeId = meta.id; progress = 0f; stage = Stage.SPEAKERS
            if (!SpeechPaths.available()) error("The speech models weren't found in ${Paths.models.absolutePath}. Reinstall KAR READER.")
            val result = SpeechEngine().transcribe(
                File(Paths.recordings, meta.audio), meta.separate, meta.speakers, meta.language,
                onProgress = { s, p -> stage = s; progress = p }, isCancelled = { flag.get() },
            )
            TranscriptFiles.save(meta.id, result.lines.map { Line(it.startMs, it.endMs, it.speaker, it.text) })
            setMeta(meta.id) { it.copy(status = "done", languages = result.languages, error = if (result.lines.isEmpty()) "No speech was found in this recording." else "") }
        } catch (e: CancellationException) {
            setMeta(meta.id) { it.copy(status = "failed", error = "Cancelled.") }
        } catch (t: Throwable) {
            setMeta(meta.id) { it.copy(status = "failed", error = t.message ?: t.javaClass.simpleName) }
        } finally {
            activeId = null
            flags.remove(meta.id)
            if (pending.decrementAndGet() == 0) busy = false
        }
    }
}
