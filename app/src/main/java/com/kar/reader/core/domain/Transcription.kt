package com.kar.reader.core.domain

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Pure transcription logic (no Android): turning "who spoke when" into recognizer-sized chunks,
 * linking speakers across long recordings, and formatting transcripts. Unit-tested on the JVM.
 */

/** A stretch of audio attributed to one speaker. Times are in seconds. */
data class SpeechSpan(val start: Float, val end: Float, val speaker: Int) {
    val duration: Float get() = end - start
}

object TranscriptPlanner {
    /** Whisper reads at most 30 s at a time; leave room for its padding. */
    const val MAX_CHUNK_SEC = 28f

    /**
     * Merges consecutive spans of the same speaker separated by short pauses, drops fragments too short
     * to hold a word, then splits anything longer than [maxChunk] into near-equal pieces.
     * [cutNear] may move a split point to a quieter moment (it receives the ideal time, returns the chosen time).
     */
    fun plan(
        spans: List<SpeechSpan>,
        maxChunk: Float = MAX_CHUNK_SEC,
        mergeGap: Float = 0.8f,
        minDuration: Float = 0.3f,
        cutNear: (ideal: Float, earliest: Float, latest: Float) -> Float = { ideal, _, _ -> ideal },
    ): List<SpeechSpan> {
        val sorted = spans.filter { it.end > it.start }.sortedBy { it.start }
        val merged = ArrayList<SpeechSpan>()
        for (s in sorted) {
            val last = merged.lastOrNull()
            if (last != null && last.speaker == s.speaker && s.start - last.end <= mergeGap && s.end - last.start <= maxChunk) {
                merged[merged.lastIndex] = last.copy(end = max(last.end, s.end))
            } else {
                merged += s
            }
        }
        val out = ArrayList<SpeechSpan>()
        for (s in merged) {
            if (s.duration < minDuration) continue
            if (s.duration <= maxChunk) { out += s; continue }
            val pieces = kotlin.math.ceil(s.duration / maxChunk).toInt()
            val step = s.duration / pieces
            var from = s.start
            for (i in 1 until pieces) {
                val ideal = s.start + step * i
                // Never let a piece exceed maxChunk, and never cut so early that the next piece would.
                val latest = min(from + maxChunk, s.end - 0.5f)
                val earliest = max(from + 1f, ideal - 3f)
                val cut = cutNear(ideal, min(earliest, latest), latest).coerceIn(min(earliest, latest), latest)
                out += SpeechSpan(from, cut, s.speaker)
                from = cut
            }
            out += SpeechSpan(from, s.end, s.speaker)
        }
        return out
    }

    /** Spans covering the whole recording with one speaker, for when speaker separation is off. */
    fun singleSpeaker(totalSec: Float): List<SpeechSpan> = if (totalSec <= 0f) emptyList() else listOf(SpeechSpan(0f, totalSec, 0))

    /** Window boundaries for diarizing long recordings in parts (memory stays bounded). */
    fun windows(totalSec: Float, windowSec: Float, cutNear: (ideal: Float, earliest: Float, latest: Float) -> Float = { i, _, _ -> i }): List<ClosedFloatingPointRange<Float>> {
        if (totalSec <= windowSec * 1.25f) return listOf(0f..totalSec)
        val result = ArrayList<ClosedFloatingPointRange<Float>>()
        var from = 0f
        while (totalSec - from > windowSec * 1.25f) {
            val ideal = from + windowSec
            val cut = cutNear(ideal, ideal - 20f, ideal + 20f).coerceIn(from + windowSec / 2, totalSec)
            result += from..cut
            from = cut
        }
        result += from..totalSec
        return result
    }
}

/** Finds a quiet moment to cut audio, so a split does not fall in the middle of a word. */
object QuietCut {
    /**
     * Returns the time (seconds, relative to the start of [samples]) of the quietest 20 ms frame between
     * [earliest] and [latest]. [samples] are mono floats at [sampleRate].
     */
    fun find(samples: FloatArray, sampleRate: Int, earliest: Float, latest: Float): Float {
        val frame = max(1, sampleRate / 50)
        val from = (earliest * sampleRate).toInt().coerceIn(0, samples.size)
        val to = (latest * sampleRate).toInt().coerceIn(from, samples.size)
        if (to - from < frame) return (from + to) / 2f / sampleRate
        var best = from
        var bestEnergy = Double.MAX_VALUE
        var i = from
        while (i + frame <= to) {
            var e = 0.0
            for (j in i until i + frame) e += samples[j] * samples[j]
            if (e < bestEnergy) { bestEnergy = e; best = i }
            i += frame
        }
        return (best + frame / 2f) / sampleRate
    }
}

/**
 * Keeps speaker identities consistent across diarization windows. Each window's speakers are compared with
 * the speakers found so far by voice embedding (cosine similarity); the best one-to-one matches above
 * [threshold] are joined, the rest become new speakers. With [maxSpeakers] set, no new speaker is created
 * past that number: the closest existing one is used instead.
 */
class SpeakerLinker(private val threshold: Float = 0.5f, private val maxSpeakers: Int = 0) {
    private val centroids = ArrayList<FloatArray>()
    private val weights = ArrayList<Float>()

    val count: Int get() = centroids.size

    /** [local] holds one embedding per local speaker (null if it could not be computed). Returns global ids. */
    fun link(local: List<FloatArray?>, durations: List<Float> = local.map { 1f }): IntArray {
        val result = IntArray(local.size) { -1 }
        val pairs = ArrayList<Triple<Int, Int, Float>>()
        local.forEachIndexed { li, e -> if (e != null) centroids.forEachIndexed { gi, c -> pairs += Triple(li, gi, cosine(e, c)) } }
        pairs.sortByDescending { it.third }
        val usedGlobal = HashSet<Int>()
        for ((li, gi, sim) in pairs) {
            if (sim < threshold || result[li] != -1 || gi in usedGlobal) continue
            result[li] = gi; usedGlobal += gi
        }
        for (li in local.indices) {
            if (result[li] != -1) continue
            val e = local[li]
            val atLimit = maxSpeakers > 0 && centroids.size >= maxSpeakers
            if (atLimit || e == null) {
                // Closest unused speaker if any, else closest overall, else speaker 0.
                val candidates = centroids.indices.filter { it !in usedGlobal }.ifEmpty { centroids.indices.toList() }
                val gi = if (e == null || candidates.isEmpty()) candidates.firstOrNull() ?: newCentroid(e) else candidates.maxBy { cosine(e, centroids[it]) }
                result[li] = gi; usedGlobal += gi
            } else {
                result[li] = newCentroid(e)
                usedGlobal += result[li]
            }
        }
        // Update centroids with the new evidence (duration-weighted running mean).
        local.forEachIndexed { li, e ->
            if (e == null) return@forEachIndexed
            val gi = result[li]
            val w = weights[gi]; val add = durations.getOrElse(li) { 1f }.coerceAtLeast(0.1f)
            val c = centroids[gi]
            if (c.size != e.size) centroids[gi] = e.copyOf()
            else for (k in c.indices) c[k] = (c[k] * w + e[k] * add) / (w + add)
            weights[gi] = w + add
        }
        return result
    }

    private fun newCentroid(e: FloatArray?): Int {
        centroids += e?.copyOf() ?: FloatArray(0)
        weights += 0f
        return centroids.lastIndex
    }

    companion object {
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.isEmpty() || a.size != b.size) return -1f
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            if (na == 0.0 || nb == 0.0) return -1f
            return (dot / (sqrt(na) * sqrt(nb))).toFloat()
        }
    }
}

/** A finished line of the transcript. Times in milliseconds; speaker counts from 0. */
data class TranscriptLine(val startMs: Long, val endMs: Long, val speaker: Int, val text: String)

object TranscriptText {
    private val noise = Regex("""^[\s\[(（【]*(blank_audio|blank audio|music|applause|laughter|silence|noise|inaudible|no speech|音乐|संगीत)[\s\])）】.]*$""", RegexOption.IGNORE_CASE)

    /** Tidies recognizer output: trims, collapses spaces, drops placeholder-only results such as "[BLANK_AUDIO]". */
    fun clean(raw: String): String {
        val t = raw.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || noise.matches(t)) return ""
        return t
    }

    /** Renumbers speakers in order of first appearance so the first person to talk is "Speaker 1". */
    fun renumberByAppearance(lines: List<TranscriptLine>): List<TranscriptLine> {
        val map = LinkedHashMap<Int, Int>()
        lines.sortedBy { it.startMs }.forEach { map.getOrPut(it.speaker) { map.size } }
        return lines.map { it.copy(speaker = map[it.speaker] ?: it.speaker) }
    }

    /** Joins consecutive lines of the same speaker into paragraphs (gap under [gapMs]). */
    fun paragraphs(lines: List<TranscriptLine>, gapMs: Long = 2_000): List<TranscriptLine> {
        val out = ArrayList<TranscriptLine>()
        for (l in lines.sortedBy { it.startMs }) {
            if (l.text.isBlank()) continue
            val last = out.lastOrNull()
            if (last != null && last.speaker == l.speaker && l.startMs - last.endMs <= gapMs) {
                out[out.lastIndex] = last.copy(endMs = l.endMs, text = last.text + " " + l.text)
            } else out += l
        }
        return out
    }

    /** Share of speaking time per speaker, in whole percent, summing to 100 when anyone spoke. */
    fun talkShare(lines: List<TranscriptLine>): Map<Int, Int> {
        val totals = lines.groupBy { it.speaker }.mapValues { (_, v) -> v.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) } }
        val sum = totals.values.sum()
        if (sum <= 0) return totals.mapValues { 0 }
        // Largest-remainder rounding so the shares add up to exactly 100.
        val raw = totals.mapValues { it.value * 100.0 / sum }
        val floors = raw.mapValues { it.value.toInt() }.toMutableMap()
        var left = 100 - floors.values.sum()
        raw.entries.sortedByDescending { it.value - it.value.toInt() }.forEach { if (left > 0) { floors[it.key] = floors.getValue(it.key) + 1; left-- } }
        return floors
    }

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    private fun srtTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", t / 3_600_000, (t / 60_000) % 60, (t / 1000) % 60, t % 1000)
    }

    fun plainText(title: String, lines: List<TranscriptLine>, names: (Int) -> String): String = buildString {
        appendLine(title)
        appendLine()
        for (p in paragraphs(lines)) {
            appendLine("[${clock(p.startMs)}] ${names(p.speaker)}: ${p.text}")
            appendLine()
        }
    }.trimEnd() + "\n"

    fun markdown(title: String, subtitle: String, lines: List<TranscriptLine>, names: (Int) -> String): String = buildString {
        appendLine("# ${title.replace("\n", " ")}")
        appendLine()
        if (subtitle.isNotBlank()) { appendLine("_${subtitle}_"); appendLine() }
        for (p in paragraphs(lines)) {
            appendLine("**${names(p.speaker)}** `${clock(p.startMs)}`  ")
            appendLine(p.text)
            appendLine()
        }
    }.trimEnd() + "\n"

    /** SubRip subtitles; each cue is one recognized chunk, prefixed with the speaker's name. */
    fun srt(lines: List<TranscriptLine>, names: (Int) -> String): String = buildString {
        lines.filter { it.text.isNotBlank() }.sortedBy { it.startMs }.forEachIndexed { i, l ->
            appendLine(i + 1)
            appendLine("${srtTime(l.startMs)} --> ${srtTime(max(l.endMs, l.startMs + 500))}")
            appendLine("${names(l.speaker)}: ${l.text}")
            appendLine()
        }
    }
}

/** Mono 16-bit PCM WAV files: what the recorder writes and what imports are converted to. */
object WavFormat {
    const val HEADER_BYTES = 44

    fun header(sampleRate: Int, dataBytes: Long): ByteArray {
        val b = ByteArray(HEADER_BYTES)
        fun str(at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
        fun i32(at: Int, v: Long) { for (k in 0..3) b[at + k] = ((v shr (8 * k)) and 0xFF).toByte() }
        fun i16(at: Int, v: Int) { b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v shr 8) and 0xFF).toByte() }
        val data = dataBytes.coerceIn(0, 0xFFFFFFFFL - 36)
        str(0, "RIFF"); i32(4, 36 + data); str(8, "WAVE")
        str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, 1); i32(24, sampleRate.toLong()); i32(28, sampleRate * 2L); i16(32, 2); i16(34, 16)
        str(36, "data"); i32(40, data)
        return b
    }

    data class Info(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataOffset: Long, val dataBytes: Long) {
        val frames: Long get() = dataBytes / (channels * bitsPerSample / 8).coerceAtLeast(1)
        val durationSec: Float get() = frames.toFloat() / sampleRate
    }

    /**
     * Reads the header of a PCM WAV file. [fileLength] lets a recording whose header was never finalized
     * (the app was stopped mid-recording) still be read: its data runs to the end of the file.
     */
    fun parse(head: ByteArray, fileLength: Long): Info? {
        fun u32(at: Int) = (0..3).fold(0L) { acc, k -> acc or ((head[at + k].toLong() and 0xFF) shl (8 * k)) }
        fun u16(at: Int) = (head[at].toInt() and 0xFF) or ((head[at + 1].toInt() and 0xFF) shl 8)
        fun tag(at: Int) = String(head, at, 4, Charsets.US_ASCII)
        if (head.size < 12 || tag(0) != "RIFF" || tag(8) != "WAVE") return null
        var pos = 12
        var rate = 0; var channels = 0; var bits = 0; var format = 0
        while (pos + 8 <= head.size) {
            val id = tag(pos); val size = u32(pos + 4)
            if (id == "fmt " && pos + 24 <= head.size) {
                format = u16(pos + 8); channels = u16(pos + 10); rate = u32(pos + 12).toInt(); bits = u16(pos + 22)
            }
            if (id == "data") {
                if (format != 1 || rate <= 0 || channels <= 0 || bits != 16) return null
                val offset = (pos + 8).toLong()
                val available = (fileLength - offset).coerceAtLeast(0)
                val declared = if (size == 0L || size == 0xFFFFFFFFL) available else size
                val bytes = min(declared, available)
                return Info(rate, channels, bits, offset, bytes - bytes % (channels * 2))
            }
            pos += 8 + size.toInt().coerceAtLeast(0) + (size.toInt() and 1)
            if (size > Int.MAX_VALUE) return null
        }
        return null
    }

    fun pcm16ToFloat(bytes: ByteArray, count: Int = bytes.size / 2): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) {
            val v = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
            out[i] = v / 32768f
        }
        return out
    }

    fun floatToPcm16(samples: FloatArray, count: Int = samples.size, into: ByteArray = ByteArray(count * 2)): ByteArray {
        for (i in 0 until count) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
            into[2 * i] = (v and 0xFF).toByte(); into[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return into
    }
}

/**
 * Streaming conversion of decoded audio (any rate, any channel count) to 16 kHz mono. Channels are averaged;
 * downsampling uses a box filter over each output period followed by linear interpolation, which is enough
 * for speech recognition.
 */
class MonoResampler(private val inRate: Int, private val channels: Int, private val outRate: Int = 16_000) {
    private val ratio = inRate.toDouble() / outRate
    private var position = 0.0          // position of the next output sample, in input frames since the start
    private var consumed = 0L           // input frames received before the current buffer
    private var tail = FloatArray(0)    // last input frames kept for interpolation across buffers

    /** Feeds interleaved float samples; returns the 16 kHz mono samples that are ready. */
    fun feed(interleaved: FloatArray, count: Int = interleaved.size): FloatArray {
        val frames = count / channels
        val mono = FloatArray(tail.size + frames)
        tail.copyInto(mono)
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) s += interleaved[f * channels + c]
            mono[tail.size + f] = s / channels
        }
        val base = consumed - tail.size  // input frame index of mono[0]
        val half = max(0.0, ratio / 2 - 0.5)
        val out = ArrayList<Float>(((frames / ratio) + 2).toInt())
        while (true) {
            val center = position - base
            val hi = center + half + 1
            if (hi >= mono.size) break
            val v = if (half < 0.5) {
                val i = center.toInt(); val t = (center - i).toFloat()
                mono[i] * (1 - t) + mono[min(i + 1, mono.lastIndex)] * t
            } else {
                val from = max(0, (center - half).toInt()); val to = min(mono.lastIndex, (center + half).toInt())
                var sum = 0f
                for (k in from..to) sum += mono[k]
                sum / (to - from + 1)
            }
            out += v
            position += ratio
        }
        consumed += frames
        val keepFrom = max(0, ((position - base) - half - 2).toInt())
        tail = mono.copyOfRange(min(keepFrom, mono.size), mono.size)
        return out.toFloatArray()
    }
}
