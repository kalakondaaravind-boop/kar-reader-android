package com.kar.reader.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DevanagariCleanupTest {
    @Test
    fun fixesTheEmulatorOutput() {
        // Omnilingual ASR's output for the Hindi test clip, as recorded on the emulator.
        val raw = "हर कोئई दुनिया को बदलने की सोچता है लेکिन कोئई खुद को बदलने की सोچता ही नहीं"
        assertEquals("हर कोई दुनिया को बदलने की सोचता है लेकिन कोई खुद को बदलने की सोचता ही नहीं", DevanagariCleanup.fix(raw))
    }

    @Test
    fun leavesCleanAndUrduTextAlone() {
        assertEquals("आज का topic क्या है", DevanagariCleanup.fix("आज का topic क्या है"))
        val urdu = "ہر کوئی دنیا کو بدلنے کی سوچتا ہے"
        assertEquals(urdu, DevanagariCleanup.fix(urdu))
    }

    @Test
    fun thenRomanizes() {
        val fixed = DevanagariCleanup.fix("हर कोئई दुनिया को बदलने की सोچता है")
        assertEquals("Har koi duniya ko badalne ki sochta hai", HindiRoman.romanize(fixed))
    }
}
