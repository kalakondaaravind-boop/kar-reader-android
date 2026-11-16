package com.kar.reader.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HindiRomanTest {
    private fun r(s: String) = HindiRoman.romanize(s)

    @Test
    fun everydayWords() {
        val cases = mapOf(
            "क्या" to "Kya", "है" to "Hai", "का" to "Ka", "आज" to "Aaj", "नहीं" to "Nahin", "में" to "Mein",
            "हैं" to "Hain", "मैं" to "Main", "हाँ" to "Haan", "हूँ" to "Hoon", "लड़की" to "Ladki", "हिंदी" to "Hindi",
            "कमल" to "Kamal", "करना" to "Karna", "करते" to "Karte", "समझना" to "Samajhna", "बदलना" to "Badalna",
            "ग्रीन" to "Green", "ज़रूर" to "Zaroor", "फ़िल्म" to "Film", "धर्म" to "Dharm", "संबंध" to "Sambandh",
            "न" to "Na", "आ" to "Aa",
        )
        cases.forEach { (hi, roman) -> assertEquals(hi, roman, r(hi)) }
    }

    @Test
    fun sentencesKeepEnglishAndPunctuation() {
        assertEquals("Aaj ka topic green revolution hai.", r("आज का topic green revolution है।"))
        assertEquals("Sir, kya ye exam mein aaega? Haan.", r("Sir, क्या ये exam में आएगा? हाँ।"))
    }

    @Test
    fun digitsAndUntouchedText() {
        assertEquals("2026 mein", r("२०२६ में"))
        assertEquals("Plain English stays.", r("Plain English stays."))
        assertFalse(HindiRoman.hasDevanagari("hello"))
        assertTrue(HindiRoman.hasDevanagari("hello नमस्ते"))
    }

    @Test
    fun precomposedAndCombiningNuktaAgree() {
        assertEquals(r("ज़रूर"), r("ज़रूर"))
    }
}
