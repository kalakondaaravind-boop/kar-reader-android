package com.kar.reader.desktop

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import com.kar.reader.core.domain.DevanagariCleanup
import com.kar.reader.core.domain.LanguageRouting
import com.kar.reader.core.domain.QuietCut
import com.kar.reader.core.domain.Recognizer
import com.kar.reader.core.domain.Route
import com.kar.reader.core.domain.SpeakerLinker
import com.kar.reader.core.domain.SpeechSpan
import com.kar.reader.core.domain.TranscriptLine
import com.kar.reader.core.domain.TranscriptPlanner
import com.kar.reader.core.domain.TranscriptText
import com.kar.reader.core.domain.WhisperLanguages
import java.io.File
import java.util.concurrent.CancellationException

/** Where the bundled speech models are. They are installed with the app; nothing is downloaded. */
object SpeechPaths {
    private fun f(rel: String) = File(Paths.models, rel).absolutePath
    val segmentation get() = f("segmentation.onnx")
    val embedding get() = f("embedding.onnx")
    val whisperEncoder get() = f("whisper/encoder.int8.onnx")
    val whisperDecoder get() = f("whisper/decoder.int8.onnx")
    val whisperTokens get() = f("whisper/tokens.txt")
    val omniModel get() = f("omnilingual/model.int8.onnx")
    val omniTokens get() = f("omnilingual/tokens.txt")
    const val description = "Whisper base (English and language identification) · Omnilingual ASR 300M (Hindi, Hinglish and other languages) · " +
        "pyannote segmentation 3.0 · 3D-Speaker ERes2Net voice embeddings. All run on this computer."

    fun hasOmnilingual() = File(omniModel).isFile && File(omniTokens).isFile
    fun available() = listOf(segmentation, embedding, whisperEncoder, whisperDecoder, whisperTokens).all { File(it).isFile }
}

enum class Stage { SPEAKERS, WORDS }

data class EngineResult(val lines: List<TranscriptLine>, val speakerCount: Int, val languages: String)

/**
 * The same pipeline as the Android app, on the desktop Java API of sherpa-onnx: pyannote segmentation plus speaker
 * embeddings tell the voices apart; Whisper (English) or Omnilingual (Hindi, Hinglish, other chosen languages) write the words.
 */
class SpeechEngine(private val windowSec: Float = WINDOW_SEC) {
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    fun transcribe(
        wav: File, separateSpeakers: Boolean, expectedSpeakers: Int, language: String,
        onProgress: (Stage, Float) -> Unit, isCancelled: () -> Boolean,
    ): EngineResult = WavReader(wav).use { reader ->
        val total = reader.durationSec
        val spans = if (separateSpeakers && total > 1f) diarize(reader, total, expectedSpeakers, onProgress, isCancelled) else TranscriptPlanner.singleSpeaker(total)
        check(isCancelled)
        val chunks = TranscriptPlanner.plan(spans) { ideal, earliest, latest -> quietMoment(reader, total, ideal, earliest, latest) }
        onProgress(Stage.WORDS, 0f)
        val hasOmni = SpeechPaths.hasOmnilingual()
        val chosen = language.takeIf { code -> WhisperLanguages.all.any { it.first == code } } ?: ""
        val auto = chosen.isEmpty()
        val totalWork = chunks.size * (if (auto) 2 else 1)
        var done = 0
        fun step() { done++; onProgress(Stage.WORDS, done.toFloat() / totalWork.coerceAtLeast(1)) }

        val languages: List<String> = if (auto) {
            // Automatic: English, Hindi or Hinglish only. Other languages are written only when chosen by hand.
            val raw = identifyLanguages(reader, chunks, isCancelled) { step() }
            if (System.getenv("KAR_DEBUG") != null) System.err.println("LID raw: $raw chunks=${chunks.map { "%.1f-%.1f".format(it.start, it.end) }}")
            val detected = raw.map { LanguageRouting.automatic(it) }
            LanguageRouting.smooth(chunks, detected)
        } else List(chunks.size) { chosen }
        val routes = languages.map { LanguageRouting.route(it, hasOmni) }

        val texts = arrayOfNulls<String>(chunks.size)
        for ((route, indices) in chunks.indices.groupBy { routes[it] }) {
            check(isCancelled)
            val recognizer = OfflineRecognizer(recognizerConfig(route))
            try {
                for (i in indices) {
                    check(isCancelled)
                    val c = chunks[i]
                    val samples = reader.read(c.start, c.end)
                    if (samples.size >= SPEECH_RATE / 5) {
                        val stream = recognizer.createStream()
                        try {
                            stream.acceptWaveform(samples, SPEECH_RATE)
                            recognizer.decode(stream)
                            val raw = recognizer.getResult(stream).text
                            texts[i] = TranscriptText.clean(if (languages[i] == "hi" || languages[i] == "ur") DevanagariCleanup.fix(raw) else raw)
                        } finally {
                            stream.release()
                        }
                    }
                    step()
                }
            } finally {
                recognizer.release()
            }
        }
        val lines = ArrayList<TranscriptLine>()
        val spoken = ArrayList<String>()
        chunks.forEachIndexed { i, c ->
            val text = texts[i].orEmpty()
            if (text.isNotEmpty()) {
                lines += TranscriptLine((c.start * 1000).toLong(), (c.end * 1000).toLong(), c.speaker, text)
                spoken += if (languages[i] == "ur") "hi" else languages[i]
            }
        }
        val renumbered = TranscriptText.renumberByAppearance(lines)
        EngineResult(renumbered, renumbered.map { it.speaker }.distinct().size.coerceAtLeast(if (renumbered.isEmpty()) 0 else 1), WhisperLanguages.summarize(spoken))
    }

    private fun recognizerConfig(route: Route): OfflineRecognizerConfig {
        val model = when (route.recognizer) {
            Recognizer.ENGLISH, Recognizer.WHISPER_BASE -> OfflineModelConfig.builder()
                .setWhisper(OfflineWhisperModelConfig.builder().setEncoder(SpeechPaths.whisperEncoder).setDecoder(SpeechPaths.whisperDecoder).setLanguage(route.whisperLanguage).setTask("transcribe").build())
                .setTokens(SpeechPaths.whisperTokens).setModelType("whisper").setNumThreads(threads).setDebug(false).build()
            Recognizer.OMNILINGUAL -> OfflineModelConfig.builder()
                .setOmnilingual(OfflineOmnilingualAsrCtcModelConfig.builder().setModel(SpeechPaths.omniModel).build())
                .setTokens(SpeechPaths.omniTokens).setNumThreads(threads).setDebug(false).build()
        }
        return OfflineRecognizerConfig.builder().setOfflineModelConfig(model).build()
    }

    /** The Java API reports the language's English name (e.g. "Hindi"); the rest of the engine works with codes. */
    private fun toCode(result: String): String {
        val r = result.trim()
        if (r.isEmpty()) return ""
        WhisperLanguages.all.firstOrNull { it.first.equals(r, true) || it.second.equals(r, true) }?.let { return it.first }
        return when (r.lowercase()) { "burmese" -> "my"; "mandarin" -> "zh"; else -> r.lowercase() }
    }

    private fun identifyLanguages(reader: WavReader, chunks: List<SpeechSpan>, isCancelled: () -> Boolean, onEach: () -> Unit): List<String> {
        val lid = SpokenLanguageIdentification(
            SpokenLanguageIdentificationConfig.builder()
                .setWhisper(SpokenLanguageIdentificationWhisperConfig.builder().setEncoder(SpeechPaths.whisperEncoder).setDecoder(SpeechPaths.whisperDecoder).build())
                .setNumThreads(threads).setDebug(false).build(),
        )
        try {
            return chunks.map { c ->
                check(isCancelled)
                val code = if (c.duration < 1.5f) "" else {
                    val stream = lid.createStream()
                    try {
                        stream.acceptWaveform(reader.read(c.start, c.end), SPEECH_RATE)
                        toCode(lid.compute(stream).trim())
                    } finally {
                        stream.release()
                    }
                }
                onEach()
                code
            }
        } finally {
            lid.release()
        }
    }

    private fun diarize(reader: WavReader, total: Float, expectedSpeakers: Int, onProgress: (Stage, Float) -> Unit, isCancelled: () -> Boolean): List<SpeechSpan> {
        val windows = TranscriptPlanner.windows(total, windowSec) { ideal, earliest, latest -> quietMoment(reader, total, ideal, earliest, latest) }
        val single = windows.size == 1
        val embeddingConfig = SpeakerEmbeddingExtractorConfig.builder().setModel(SpeechPaths.embedding).setNumThreads(threads).setDebug(false).build()
        val diarizer = OfflineSpeakerDiarization(
            OfflineSpeakerDiarizationConfig.builder()
                .setSegmentation(
                    OfflineSpeakerSegmentationModelConfig.builder()
                        .setPyannote(OfflineSpeakerSegmentationPyannoteModelConfig.builder().setModel(SpeechPaths.segmentation).build())
                        .setNumThreads(threads).setDebug(false).build(),
                )
                .setEmbedding(embeddingConfig)
                .setClustering(FastClusteringConfig.builder().setNumClusters(if (single && expectedSpeakers > 0) expectedSpeakers else -1).setThreshold(CLUSTER_THRESHOLD).build())
                .setMinDurationOn(0.3f).setMinDurationOff(0.5f).build(),
        )
        val extractor = if (single) null else SpeakerEmbeddingExtractor(embeddingConfig)
        val linker = SpeakerLinker(threshold = LINK_THRESHOLD, maxSpeakers = expectedSpeakers)
        val out = ArrayList<SpeechSpan>()
        try {
            onProgress(Stage.SPEAKERS, 0f)
            windows.forEachIndexed { wi, w ->
                check(isCancelled)
                val samples = reader.read(w.start, w.endInclusive)
                onProgress(Stage.SPEAKERS, (wi + 0.05f) / windows.size)
                // process(), not processWithCallback(): the callback path needs a specific lambda signature on the native side.
                val segments = diarizer.process(samples)
                val local = segments.map { SpeechSpan(it.start, it.end, it.speaker) }
                if (extractor == null) {
                    out += local
                } else {
                    val ids = local.map { it.speaker }.distinct().sorted()
                    val embeddings = ids.map { id -> embed(extractor, samples, local.filter { it.speaker == id }) }
                    val durations = ids.map { id -> local.filter { it.speaker == id }.sumOf { it.duration.toDouble() }.toFloat() }
                    val global = linker.link(embeddings, durations)
                    val map = ids.indices.associate { ids[it] to global[it] }
                    out += local.map { SpeechSpan(w.start + it.start, w.start + it.end, map.getValue(it.speaker)) }
                }
                onProgress(Stage.SPEAKERS, (wi + 1f) / windows.size)
            }
        } finally {
            diarizer.release()
            extractor?.release()
        }
        return out
    }

    private fun embed(extractor: SpeakerEmbeddingExtractor, samples: FloatArray, spans: List<SpeechSpan>): FloatArray? {
        val picked = ArrayList<FloatArray>()
        var seconds = 0f
        for (s in spans.sortedByDescending { it.duration }) {
            if (seconds >= 20f) break
            val from = (s.start * SPEECH_RATE).toInt().coerceIn(0, samples.size)
            val to = (s.end * SPEECH_RATE).toInt().coerceIn(from, samples.size)
            if (to - from < SPEECH_RATE / 2) continue
            picked += samples.copyOfRange(from, to); seconds += s.duration
        }
        if (picked.isEmpty()) return null
        val joined = FloatArray(picked.sumOf { it.size })
        var at = 0
        picked.forEach { it.copyInto(joined, at); at += it.size }
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(joined, SPEECH_RATE)
            stream.inputFinished()
            if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    private fun quietMoment(reader: WavReader, total: Float, ideal: Float, earliest: Float, latest: Float): Float {
        val e = earliest.coerceIn(0f, total)
        val l = latest.coerceIn(e, total)
        if (l - e < 0.1f) return ideal.coerceIn(0f, total)
        return e + QuietCut.find(reader.read(e, l), SPEECH_RATE, 0f, l - e)
    }

    private fun check(isCancelled: () -> Boolean) { if (isCancelled()) throw CancellationException() }

    companion object {
        const val WINDOW_SEC = 20 * 60f
        const val CLUSTER_THRESHOLD = 0.5f
        const val LINK_THRESHOLD = 0.5f
    }
}
