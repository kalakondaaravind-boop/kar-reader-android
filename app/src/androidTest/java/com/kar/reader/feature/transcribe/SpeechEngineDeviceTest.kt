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
        private lateinit var hindi: File
        private lateinit var german: File
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
            hindi = convert("hi-hindi.wav")
            german = convert("de-german.wav")
        }

        private fun convert(asset: String): File {
            val inst = InstrumentationRegistry.getInstrumentation()
            val app = inst.targetContext
            val src = File(app.cacheDir, "src-$asset")
            inst.context.assets.open(asset).use { input -> src.outputStream().use { input.copyTo(it) } }
            val out = File(app.cacheDir, "16k-$asset")
            val ms = AudioConverter.toSpeechWav(app, Uri.fromFile(src), out, {}, { false })
            assertTrue("$asset converted to $ms ms", ms > 1_000)
            return out
        }
    }

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun run(label: String, engine: SpeechEngine, separate: Boolean, expected: Int, language: String, file: File = wav, route: com.kar.reader.core.domain.Route? = null): EngineResult {
        val started = System.currentTimeMillis()
        val r = engine.transcribe(file, separate, expected, language, { _, _ -> }, { false }, route)
        val block = StringBuilder()
        block.append("[$label] ${System.currentTimeMillis() - started} ms, speakers=${r.speakerCount}, languages=${r.languages}")
        r.lines.forEach { block.append(" | S${it.speaker + 1} ${it.startMs / 1000.0}s: ${it.text}") }
        report.appendLine(block)
        File(app.filesDir, "speech-test-report.txt").writeText(report.toString())
        Log.i("KarSpeechTest", block.toString())
        return r
    }

    @Test
    fun hindiClipThroughEveryModel() {
        // Diagnostic: the same Hindi clip through each recognizer, to judge the models against each other.
        run("Hindi via Hindi Whisper", SpeechEngine(app), false, 0, "hi", hindi, com.kar.reader.core.domain.Route(com.kar.reader.core.domain.Recognizer.HINDI, "hi"))
        run("Hindi via Whisper base", SpeechEngine(app), false, 0, "hi", hindi, com.kar.reader.core.domain.Route(com.kar.reader.core.domain.Recognizer.WHISPER_BASE, "hi"))
        run("Hindi via Omnilingual", SpeechEngine(app), false, 0, "hi", hindi, com.kar.reader.core.domain.Route(com.kar.reader.core.domain.Recognizer.OMNILINGUAL, ""))
    }

    @Test
    fun modelsAreBundled() {
        assertTrue(SpeechModels.available(app))
        assertTrue(SpeechModels.hasHindi(app))
        assertTrue(SpeechModels.hasOmnilingual(app))
    }

    @Test
    fun hindiIsDetectedAndWrittenInDevanagari() {
        val r = run("Hindi, automatic", SpeechEngine(app), separate = false, expected = 0, language = "", file = hindi)
        val text = r.lines.joinToString(" ") { it.text }
        assertTrue(report.toString(), r.languages.startsWith("hi"))
        assertTrue(report.toString(), text.count { it in '\u0900'..'\u097F' } > 10)
        val roman = com.kar.reader.core.domain.HindiRoman.romanize(text)
        report.appendLine("  Roman: $roman"); Log.i("KarSpeechTest", "  Roman: $roman")
        assertTrue(report.toString(), roman.count { it in 'a'..'z' } > 10)
    }

    @Test
    fun hindiChosenByHandUsesTheHindiModel() {
        val r = run("Hindi, chosen", SpeechEngine(app), separate = false, expected = 0, language = "hi", file = hindi)
        assertTrue(report.toString(), r.lines.joinToString(" ") { it.text }.count { it in '\u0900'..'\u097F' } > 10)
    }

    @Test
    fun otherLanguagesNeedChoosingByHand() {
        val auto = run("German, automatic", SpeechEngine(app), separate = false, expected = 0, language = "", file = german)
        assertTrue(report.toString(), auto.languages.isBlank() || auto.languages.split(',').all { it == "en" || it == "hi" })
        val chosen = run("German, chosen", SpeechEngine(app), separate = false, expected = 0, language = "de", file = german)
        assertTrue(report.toString(), chosen.languages.startsWith("de"))
        assertTrue(report.toString(), chosen.lines.joinToString(" ") { it.text }.count { it.isLetter() } > 10)
    }
}
