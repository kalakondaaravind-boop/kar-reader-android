package com.kar.reader.feature.transcribe

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real on-device speech pipeline (sherpa-onnx native libraries and the bundled models) on a recording of
 * two English speakers: import conversion, speaker separation, cross-window speaker linking and recognition.
 * Results are logged under the tag KarSpeechTest so CI can show them.
 */
@RunWith(AndroidJUnit4::class)
class SpeechEngineDeviceTest {

    companion object {
        private lateinit var wav: File
        private val report = StringBuilder()

        @JvmStatic
        @BeforeClass
        fun convert() {
            val inst = InstrumentationRegistry.getInstrumentation()
            val app = inst.targetContext
            val src = File(app.cacheDir, "two-speakers-src.wav")
            inst.context.assets.open("two-speakers-en.wav").use { input -> src.outputStream().use { input.copyTo(it) } }
            wav = File(app.cacheDir, "two-speakers-16k.wav")
            val ms = AudioConverter.toSpeechWav(app, Uri.fromFile(src), wav, {}, { false })
            report.appendLine("converted: ${ms} ms")
            assertTrue("conversion produced ${ms} ms", ms > 5_000)
        }
    }

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun run(label: String, engine: SpeechEngine, separate: Boolean, expected: Int, language: String): EngineResult {
        val started = System.currentTimeMillis()
        val r = engine.transcribe(wav, separate, expected, language, { _, _ -> }, { false })
        report.appendLine("[$label] ${System.currentTimeMillis() - started} ms, speakers=${r.speakerCount}, languages=${r.languages}")
        r.lines.forEach { report.appendLine("  S${it.speaker + 1} ${it.startMs}-${it.endMs}: ${it.text}") }
        File(app.filesDir, "speech-test-report.txt").writeText(report.toString())
        report.toString().lines().takeLast(r.lines.size + 1).forEach { Log.i("KarSpeechTest", it) }
        return r
    }

    @Test
    fun modelsAreBundled() {
        assertTrue(SpeechModels.available(app))
    }

    @Test
    fun separatesTwoKnownSpeakers() {
        val r = run("2 speakers, English", SpeechEngine(app), separate = true, expected = 2, language = "en")
        assertTrue(report.toString(), r.lines.isNotEmpty())
        assertEquals(report.toString(), 2, r.speakerCount)
    }

    @Test
    fun findsSpeakersAndLanguageAutomatically() {
        val r = run("automatic", SpeechEngine(app), separate = true, expected = 0, language = "")
        assertTrue(report.toString(), r.speakerCount >= 1)
        assertTrue(report.toString(), r.lines.joinToString(" ") { it.text }.count { it.isLetter() } > 20)
        assertTrue(report.toString(), r.languages.startsWith("en"))
    }

    @Test
    fun linksSpeakersAcrossWindows() {
        // Short windows force the long-recording path: per-window diarization, voice embeddings and linking.
        val r = run("6 s windows", SpeechEngine(app, windowSec = 6f), separate = true, expected = 0, language = "en")
        assertTrue(report.toString(), r.lines.isNotEmpty())
        assertTrue(report.toString(), r.speakerCount in 1..4)
    }

    @Test
    fun singleSpeakerModeTranscribes() {
        val r = run("no speaker separation", SpeechEngine(app), separate = false, expected = 0, language = "")
        assertTrue(report.toString(), r.lines.isNotEmpty())
        assertEquals(1, r.speakerCount)
    }
}
