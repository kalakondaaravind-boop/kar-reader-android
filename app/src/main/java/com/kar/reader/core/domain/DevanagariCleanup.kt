package com.kar.reader.core.domain

/**
 * Omnilingual ASR writes Hindi in Devanagari but sometimes slips an Urdu (Arabic-script) letter into a word:
 * "कोئई", "सोچता", "लेکिन". When a line is mostly Devanagari, those letters are replaced by their Devanagari
 * equivalents (consonants) or dropped (Urdu vowel carriers, which the surrounding Devanagari already spells out).
 * Lines that are mostly Urdu script are left alone.
 */
object DevanagariCleanup {
    private val CONSONANTS = mapOf(
        'ب' to "ब", 'پ' to "प", 'ت' to "त", 'ٹ' to "ट", 'ث' to "स", 'ج' to "ज", 'چ' to "च", 'ح' to "ह", 'خ' to "ख़",
        'د' to "द", 'ڈ' to "ड", 'ذ' to "ज़", 'ر' to "र", 'ڑ' to "ड़", 'ز' to "ज़", 'ژ' to "ज़", 'س' to "स", 'ش' to "श",
        'ص' to "स", 'ض' to "ज़", 'ط' to "त", 'ظ' to "ज़", 'غ' to "ग़", 'ف' to "फ़", 'ق' to "क़", 'ک' to "क", 'ك' to "क",
        'گ' to "ग", 'ل' to "ल", 'م' to "म", 'ن' to "न", 'ں' to "ं", 'ہ' to "ह", 'ه' to "ह", 'ۃ' to "त",
    )
    /** Vowel carriers and marks: the Devanagari around them already carries the vowel. */
    private val DROP = setOf('ا', 'آ', 'و', 'ی', 'ي', 'ے', 'ئ', 'ؤ', 'ء', 'ع', 'ھ', 'ۓ', 'ٰ', 'َ', 'ِ', 'ُ', 'ّ', 'ْ', 'ٔ')

    private fun isArabic(c: Char) = c in '؀'..'ۿ' || c in 'ݐ'..'ݿ'
    private fun isDevanagari(c: Char) = c in 'ऀ'..'ॿ'

    fun fix(text: String): String {
        val deva = text.count(::isDevanagari)
        val arabic = text.count(::isArabic)
        if (arabic == 0 || deva <= arabic) return text
        val sb = StringBuilder()
        for (c in text) {
            when {
                !isArabic(c) -> sb.append(c)
                c in CONSONANTS -> sb.append(CONSONANTS.getValue(c))
                c in DROP -> Unit
                c == '۔' -> sb.append('।')
                c == '،' -> sb.append(',')
                c == '؟' -> sb.append('?')
                else -> Unit
            }
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }
}
