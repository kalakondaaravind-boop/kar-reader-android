package com.kar.reader.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageRoutingTest {

    @Test
    fun routesEnglishHindiAndOthers() {
        assertEquals(Recognizer.ENGLISH, LanguageRouting.route("en", true, true).recognizer)
        assertEquals(Route(Recognizer.HINDI, "hi"), LanguageRouting.route("hi", true, true))
        assertEquals(Recognizer.HINDI, LanguageRouting.route("ur", true, true).recognizer)
        assertEquals(Recognizer.OMNILINGUAL, LanguageRouting.route("de", true, true).recognizer)
        assertEquals(Recognizer.OMNILINGUAL, LanguageRouting.route("te", true, true).recognizer)
    }

    @Test
    fun automaticModeIsEnglishOrHindiOnly() {
        assertEquals("hi", LanguageRouting.automatic("hi"))
        assertEquals("hi", LanguageRouting.automatic("ur"))
        assertEquals("hi", LanguageRouting.automatic("mr"))
        assertEquals("en", LanguageRouting.automatic("en"))
        assertEquals("en", LanguageRouting.automatic("de"))
        assertEquals("en", LanguageRouting.automatic("cy"))
        assertEquals("", LanguageRouting.automatic(""))
        // So automatic mode never reaches Omnilingual.
        listOf("hi", "ur", "de", "te", "fr").forEach { code ->
            val r = LanguageRouting.route(LanguageRouting.automatic(code), hasHindi = true, hasOmnilingual = true).recognizer
            assertEquals(code, true, r == Recognizer.ENGLISH || r == Recognizer.HINDI)
        }
    }

    @Test
    fun fallsBackToWhisperBaseWhenModelsAreMissing() {
        assertEquals(Route(Recognizer.WHISPER_BASE, "hi"), LanguageRouting.route("hi", false, false))
        assertEquals(Route(Recognizer.WHISPER_BASE, "de"), LanguageRouting.route("de", true, false))
        assertEquals(Recognizer.OMNILINGUAL, LanguageRouting.route("hi", false, true).recognizer)
    }

    @Test
    fun shortMisheardPieceTakesTheSpeakersLanguage() {
        val spans = listOf(SpeechSpan(0f, 20f, 0), SpeechSpan(20f, 23f, 0), SpeechSpan(23f, 45f, 0))
        assertEquals(listOf("hi", "hi", "hi"), LanguageRouting.smooth(spans, listOf("hi", "en", "hi")))
    }

    @Test
    fun longPiecesKeepTheirLanguage() {
        // A Hindi speaker who switches to a long English stretch keeps English for it.
        val spans = listOf(SpeechSpan(0f, 20f, 0), SpeechSpan(20f, 40f, 0))
        assertEquals(listOf("hi", "en"), LanguageRouting.smooth(spans, listOf("hi", "en")))
    }

    @Test
    fun eachSpeakerHasTheirOwnMainLanguage() {
        val spans = listOf(SpeechSpan(0f, 20f, 0), SpeechSpan(20f, 40f, 1), SpeechSpan(40f, 43f, 1), SpeechSpan(43f, 46f, 0))
        assertEquals(listOf("en", "hi", "hi", "en"), LanguageRouting.smooth(spans, listOf("en", "hi", "en", "hi")))
    }

    @Test
    fun urduAndHindiCountTogether() {
        val spans = listOf(SpeechSpan(0f, 10f, 0), SpeechSpan(10f, 20f, 0), SpeechSpan(20f, 23f, 0))
        val r = LanguageRouting.smooth(spans, listOf("ur", "hi", "en"))
        assertEquals("hi", r[2])
        assertEquals(Recognizer.HINDI, LanguageRouting.route(r[2], true, true).recognizer)
    }

    @Test
    fun missingGuessesUseSpeakerThenRecordingThenEnglish() {
        val spans = listOf(SpeechSpan(0f, 10f, 0), SpeechSpan(10f, 12f, 0), SpeechSpan(12f, 14f, 1))
        assertEquals(listOf("hi", "hi", "hi"), LanguageRouting.smooth(spans, listOf("hi", "", "")))
        assertEquals(listOf("en"), LanguageRouting.smooth(listOf(SpeechSpan(0f, 3f, 0)), listOf("")))
    }
}
