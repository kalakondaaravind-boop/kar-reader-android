package com.kar.reader.feature.transcribe

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.kar.reader.core.domain.QuietCut
import com.kar.reader.core.domain.SpeakerLinker
import com.kar.reader.core.domain.SpeechSpan
import com.kar.reader.core.domain.TranscriptLine
import com.kar.reader.core.domain.TranscriptPlanner
import com.kar.reader.core.domain.TranscriptText
import com.kar.reader.core.domain.WhisperLanguages
import java.io.File
import java.util.concurrent.CancellationException

/**
 * On-device speech pipeline (sherpa-onnx, ONNX Runtime): pyannote segmentation + speaker embeddings tell the
 * voices apart, and multilingual Whisper writes the words. Models ship inside the APK under assets/models.
 * Nothing here touches the network.
 */
object SpeechModels {
    const val SEGMENTATION = "models/segmentation.onnx"
    const val EMBEDDING = "models/embedding.onnx"
    const val WHISPER_ENCODER = "models/whisper/encoder.int8.onnx"
    const val WHISPER_DECODER = "models/whisper/decoder.int8.onnx"
    const val WHISPER_TOKENS = "models/whisper/tokens.txt"
    /** Describes the bundled models for the About and privacy screens. */
    const val DESCRIPTION = "Whisper base (multilingual, int8) · pyannote segmentation 3.0 · 3D-Speaker ERes2Net embeddings"

    fun available(context: Context): Boolean = runCatching {
        val root = context.assets.list("models").orEmpty().toSet()
        val whisper = context.assets.list("models/whisper").orEmpty().toSet()
        "segmentation.onnx" in root && "embedding.onnx" in root &&
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt").all { it in whisper }
    }.getOrDefault(false)
}

enum class Stage { SPEAKERS, WORDS }

data class EngineResult(val lines: List<TranscriptLine>, val speakerCount: Int, val languages: String)

class SpeechEngine(private val context: Context) {
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /**
     * Transcribes [wav]. [expectedSpeakers] 0 means "find out". Progress is reported per stage from 0 to 1.
     * Long recordings are diarized in ~20-minute windows so memory stays bounded; speakers are then matched
     * across windows by voice.
     */
    fun transcribe(
        wav: File,
        separateSpeakers: Boolean,
        expectedSpeakers: Int,
        language: String,
        onProgress: (Stage, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): EngineResult = WavReader(wav).use { reader ->
        val total = reader.durationSec
        val spans = if (separateSpeakers && total > 1f) diarize(reader, total, expectedSpeakers, onProgress, isCancelled) else TranscriptPlanner.singleSpeaker(total)
        check(isCancelled)
        val chunks = TranscriptPlanner.plan(spans) { ideal, earliest, latest ->
            if (latest - earliest < 0.1f) ideal else earliest + QuietCut.find(reader.read(earliest, latest), SPEECH_RATE, 0f, latest - earliest)
        }
        onProgress(Stage.WORDS, 0f)
        val lines = ArrayList<TranscriptLine>()
        val languages = ArrayList<String>()
        val recognizer = OfflineRecognizer(
            assetManager = context.assets,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = SpeechModels.WHISPER_ENCODER,
                        decoder = SpeechModels.WHISPER_DECODER,
                        language = language.takeIf { code -> WhisperLanguages.all.any { it.first == code } } ?: "",
                        task = "transcribe",
                    ),
                    tokens = SpeechModels.WHISPER_TOKENS,
                    modelType = "whisper",
                    numThreads = threads,
                ),
            ),
        )
        try {
            chunks.forEachIndexed { i, c ->
                check(isCancelled)
                val samples = reader.read(c.start, c.end)
                if (samples.size >= SPEECH_RATE / 5) {
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples, SPEECH_RATE)
                        recognizer.decode(stream)
                        val result = recognizer.getResult(stream)
                        val text = TranscriptText.clean(result.text)
                        if (text.isNotEmpty()) {
                            lines += TranscriptLine((c.start * 1000).toLong(), (c.end * 1000).toLong(), c.speaker, text)
                            languages += result.lang
                        }
                    } finally {
                        stream.release()
                    }
                }
                onProgress(Stage.WORDS, (i + 1f) / chunks.size)
            }
        } finally {
            recognizer.release()
        }
        val renumbered = TranscriptText.renumberByAppearance(lines)
        EngineResult(renumbered, renumbered.map { it.speaker }.distinct().size.coerceAtLeast(if (renumbered.isEmpty()) 0 else 1), WhisperLanguages.summarize(languages))
    }

    private fun diarize(
        reader: WavReader,
        total: Float,
        expectedSpeakers: Int,
        onProgress: (Stage, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): List<SpeechSpan> {
        val windows = TranscriptPlanner.windows(total, WINDOW_SEC) { ideal, earliest, latest ->
            earliest + QuietCut.find(reader.read(earliest, latest), SPEECH_RATE, 0f, latest - earliest)
        }
        val single = windows.size == 1
        val diarizer = OfflineSpeakerDiarization(
            assetManager = context.assets,
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = SpeechModels.SEGMENTATION),
                    numThreads = threads,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(model = SpeechModels.EMBEDDING, numThreads = threads),
                // With one window the known count can be enforced exactly; across windows it caps the linker instead.
                clustering = FastClusteringConfig(numClusters = if (single && expectedSpeakers > 0) expectedSpeakers else -1, threshold = CLUSTER_THRESHOLD),
                minDurationOn = 0.3f,
                minDurationOff = 0.5f,
            ),
        )
        val extractor = if (single) null else SpeakerEmbeddingExtractor(context.assets, SpeakerEmbeddingExtractorConfig(model = SpeechModels.EMBEDDING, numThreads = threads))
        val linker = SpeakerLinker(threshold = LINK_THRESHOLD, maxSpeakers = expectedSpeakers)
        val out = ArrayList<SpeechSpan>()
        try {
            onProgress(Stage.SPEAKERS, 0f)
            windows.forEachIndexed { wi, w ->
                check(isCancelled)
                val samples = reader.read(w.start, w.endInclusive)
                val segments = diarizer.processWithCallback(samples, callback = { done, all, _ ->
                    onProgress(Stage.SPEAKERS, (wi + done.toFloat() / all.coerceAtLeast(1)) / windows.size)
                    0
                })
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

    /** Voice embedding of one speaker in a window, from up to 20 s of their longest turns. */
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

    private fun check(isCancelled: () -> Boolean) { if (isCancelled()) throw CancellationException() }

    companion object {
        const val WINDOW_SEC = 20 * 60f
        const val CLUSTER_THRESHOLD = 0.5f
        const val LINK_THRESHOLD = 0.5f
    }
}
