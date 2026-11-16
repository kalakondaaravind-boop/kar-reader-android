package com.kar.reader.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class TranscriptionTest {

    @Test
    fun planMergesSameSpeakerAcrossShortPauses() {
        val plan = TranscriptPlanner.plan(listOf(SpeechSpan(0f, 3f, 0), SpeechSpan(3.5f, 6f, 0), SpeechSpan(6.2f, 9f, 1)))
        assertEquals(2, plan.size)
        assertEquals(SpeechSpan(0f, 6f, 0), plan[0])
        assertEquals(1, plan[1].speaker)
    }

    @Test
    fun planKeepsSpeakersApartAndDropsTinyFragments() {
        val plan = TranscriptPlanner.plan(listOf(SpeechSpan(0f, 2f, 0), SpeechSpan(2.1f, 2.2f, 1), SpeechSpan(2.3f, 5f, 0)))
        assertEquals(listOf(0), plan.map { it.speaker }.distinct())
        assertTrue(plan.none { it.duration < 0.3f })
    }

    @Test
    fun planSplitsLongSpansBelowTheRecognizerLimit() {
        val plan = TranscriptPlanner.plan(listOf(SpeechSpan(10f, 100f, 2)))
        assertTrue(plan.size >= 4)
        assertTrue(plan.all { it.duration <= TranscriptPlanner.MAX_CHUNK_SEC + 1e-3 })
        assertEquals(10f, plan.first().start, 1e-4f)
        assertEquals(100f, plan.last().end, 1e-4f)
        for (i in 1 until plan.size) assertEquals(plan[i - 1].end, plan[i].start, 1e-4f)
    }

    @Test
    fun planHonoursCutChooserWithinLimits() {
        val plan = TranscriptPlanner.plan(listOf(SpeechSpan(0f, 50f, 0))) { _, _, latest -> latest + 10f } // asks for too late a cut
        assertTrue(plan.all { it.duration <= TranscriptPlanner.MAX_CHUNK_SEC + 1e-3 })
    }

    @Test
    fun mergingNeverBuildsAChunkOverTheLimit() {
        val spans = (0 until 20).map { SpeechSpan(it * 3f, it * 3f + 2.8f, 0) }
        val plan = TranscriptPlanner.plan(spans)
        assertTrue(plan.all { it.duration <= TranscriptPlanner.MAX_CHUNK_SEC + 1e-3 })
    }

    @Test
    fun windowsCoverTheWholeRecording() {
        assertEquals(1, TranscriptPlanner.windows(600f, 1200f).size)
        val w = TranscriptPlanner.windows(5000f, 1200f)
        assertTrue(w.size >= 4)
        assertEquals(0f, w.first().start, 0f)
        assertEquals(5000f, w.last().endInclusive, 0f)
        for (i in 1 until w.size) assertEquals(w[i - 1].endInclusive, w[i].start, 0f)
    }

    @Test
    fun quietCutFindsTheSilentGap() {
        val rate = 16_000
        val samples = FloatArray(rate * 4) { i -> if (i in (rate * 2)..(rate * 2 + 3200)) 0f else (sin(2 * PI * 220 * i / rate) * 0.5).toFloat() }
        val t = QuietCut.find(samples, rate, 1f, 3f)
        assertTrue("cut at $t", t in 2.0f..2.2f)
    }

    @Test
    fun linkerKeepsVoicesConsistentAcrossWindows() {
        val a = floatArrayOf(1f, 0.1f, 0f); val b = floatArrayOf(0f, 1f, 0.1f); val c = floatArrayOf(0.1f, 0f, 1f)
        val linker = SpeakerLinker(threshold = 0.6f)
        val first = linker.link(listOf(a, b))
        assertEquals(listOf(0, 1), first.toList())
        val second = linker.link(listOf(floatArrayOf(0.05f, 0.95f, 0.1f), floatArrayOf(0.9f, 0.15f, 0f), c))
        assertEquals(listOf(1, 0, 2), second.toList())
        assertEquals(3, linker.count)
    }

    @Test
    fun linkerRespectsKnownSpeakerCount() {
        val linker = SpeakerLinker(threshold = 0.99f, maxSpeakers = 2)
        linker.link(listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
        val next = linker.link(listOf(floatArrayOf(0.7f, 0.7f)))
        assertEquals(2, linker.count)
        assertTrue(next[0] in 0..1)
    }

    @Test
    fun linkerNeverMapsTwoLocalSpeakersToOneGlobalWhenRoomRemains() {
        val linker = SpeakerLinker(threshold = 0.5f)
        linker.link(listOf(floatArrayOf(1f, 0f)))
        val r = linker.link(listOf(floatArrayOf(0.9f, 0.1f), floatArrayOf(0.95f, 0.05f)))
        assertTrue(r[0] != r[1])
    }

    @Test
    fun cleanDropsPlaceholders() {
        assertEquals("", TranscriptText.clean(" [BLANK_AUDIO] "))
        assertEquals("", TranscriptText.clean("(Music)"))
        assertEquals("Hello there.", TranscriptText.clean("  Hello   there. "))
    }

    @Test
    fun renumbersSpeakersByFirstAppearance() {
        val lines = listOf(TranscriptLine(5000, 6000, 3, "b"), TranscriptLine(0, 1000, 1, "a"), TranscriptLine(7000, 8000, 1, "c"))
        val r = TranscriptText.renumberByAppearance(lines)
        assertEquals(1, r[0].speaker); assertEquals(0, r[1].speaker); assertEquals(0, r[2].speaker)
    }

    @Test
    fun talkShareSumsToHundred() {
        val lines = listOf(TranscriptLine(0, 1000, 0, "a"), TranscriptLine(1000, 2000, 1, "b"), TranscriptLine(2000, 3000, 2, "c"))
        val share = TranscriptText.talkShare(lines)
        assertEquals(100, share.values.sum())
        assertTrue(share.values.all { it in 33..34 })
    }

    @Test
    fun paragraphsJoinSameSpeaker() {
        val p = TranscriptText.paragraphs(listOf(TranscriptLine(0, 1000, 0, "One."), TranscriptLine(1500, 2000, 0, "Two."), TranscriptLine(2100, 3000, 1, "Three.")))
        assertEquals(2, p.size)
        assertEquals("One. Two.", p[0].text)
    }

    @Test
    fun srtIsWellFormed() {
        val srt = TranscriptText.srt(listOf(TranscriptLine(61_250, 63_000, 0, "Hi")), { "Asha" })
        assertEquals("1\n00:01:01,250 --> 00:01:03,000\nAsha: Hi\n\n", srt)
    }

    @Test
    fun clockFormats() {
        assertEquals("0:05", TranscriptText.clock(5_400))
        assertEquals("1:02:03", TranscriptText.clock(3_723_000))
    }

    @Test
    fun markdownAndTextIncludeNames() {
        val lines = listOf(TranscriptLine(0, 1000, 0, "Hello."))
        assertTrue(TranscriptText.markdown("T", "sub", lines) { "Prof" }.contains("**Prof** `0:00`"))
        assertTrue(TranscriptText.plainText("T", lines) { "Prof" }.contains("[0:00] Prof: Hello."))
    }

    @Test
    fun wavHeaderRoundTrips() {
        val head = WavFormat.header(16_000, 32_000)
        val info = WavFormat.parse(head, 44 + 32_000L)
        assertNotNull(info)
        assertEquals(16_000, info!!.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16_000L, info.frames)
        assertEquals(1f, info.durationSec, 1e-6f)
    }

    @Test
    fun unfinishedRecordingIsReadToEndOfFile() {
        val head = WavFormat.header(16_000, 0)
        val info = WavFormat.parse(head, 44 + 64_001L)
        assertEquals(64_000L, info!!.dataBytes)
    }

    @Test
    fun rejectsNonPcmOrGarbage() {
        assertNull(WavFormat.parse(ByteArray(44), 44))
        val head = WavFormat.header(16_000, 10)
        head[20] = 3 // float format
        assertNull(WavFormat.parse(head, 54))
    }

    @Test
    fun pcmConversionRoundTrips() {
        val s = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f)
        val back = WavFormat.pcm16ToFloat(WavFormat.floatToPcm16(s))
        s.indices.forEach { assertEquals(s[it], back[it], 1e-3f) }
    }

    @Test
    fun resamplerProducesExpectedLengthAndKeepsTone() {
        val inRate = 44_100
        val total = inRate * 2
        val stereo = FloatArray(total * 2) { i -> val f = i / 2; (sin(2 * PI * 440 * f / inRate) * 0.5).toFloat() }
        val r = MonoResampler(inRate, 2)
        val out = ArrayList<Float>()
        var i = 0
        while (i < stereo.size) { val n = minOf(4410 * 2, stereo.size - i); out += r.feed(stereo.copyOfRange(i, i + n)).toList(); i += n }
        assertTrue("got ${out.size}", abs(out.size - 32_000) < 40)
        // Zero crossings of a 440 Hz tone over 2 s: about 1760.
        val crossings = (1 until out.size).count { (out[it - 1] < 0) != (out[it] < 0) }
        assertTrue("crossings $crossings", crossings in 1700..1820)
        assertTrue(out.max() in 0.4f..0.51f)
    }

    @Test
    fun resamplerUpsamples() {
        val r = MonoResampler(8_000, 1)
        val out = r.feed(FloatArray(8_000) { 0.25f })
        assertTrue(abs(out.size - 16_000) < 10)
        assertTrue(out.all { abs(it - 0.25f) < 1e-4 })
    }
}
