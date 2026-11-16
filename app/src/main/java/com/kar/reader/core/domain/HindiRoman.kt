package com.kar.reader.core.domain

/**
 * Writes Hindi (Devanagari) in Roman letters the way people type Hinglish: "क्या" → "kya", "नहीं" → "nahin",
 * "में" → "mein", "लड़की" → "ladki", "समझना" → "samajhna". Text that is not Devanagari is left as it is, so
 * English words in a Hinglish sentence are untouched.
 *
 * It follows Hindi pronunciation rules (the inherent "a" is silent at the end of a word and between a vowel and a
 * following voiced syllable), not a scholarly transliteration, so it is readable but not reversible.
 */
object HindiRoman {

    fun hasDevanagari(text: String): Boolean = text.any { it in 'ऀ'..'ॿ' }

    fun romanize(text: String): String {
        if (!hasDevanagari(text)) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isDevanagariLetter(c)) {
                var j = i
                while (j < text.length && (isDevanagariLetter(text[j]) || text[j] == '‍' || text[j] == '‌')) j++
                out.append(word(text.substring(i, j).replace("‍", "").replace("‌", "")))
                i = j
            } else {
                out.append(
                    when (c) {
                        '।' -> '.'        // ।
                        '॥' -> '.'        // ॥
                        in '०'..'९' -> '0' + (c - '०')
                        else -> c
                    },
                )
                i++
            }
        }
        return capitalizeSentences(out.toString().replace(Regex(" \\.(?=\\s|$)"), "."))
    }

    // ---- one word ---------------------------------------------------------------------------------------------

    private sealed interface Piece
    /** A consonant (already in Roman letters) and its vowel: null = inherent "a", "" = none (virama). */
    private data class Cons(val roman: String, var vowel: String?, var nasal: Boolean = false) : Piece
    private data class Vowel(val roman: String, var nasal: Boolean = false) : Piece
    private data class Other(val roman: String) : Piece

    private fun word(w: String): String {
        val units = ArrayList<Piece>()
        var k = 0
        while (k < w.length) {
            val c = w[k]
            val next = w.getOrNull(k + 1)
            when {
                c in CONSONANTS -> {
                    var roman = CONSONANTS.getValue(c)
                    var step = 1
                    if (next == '़') { roman = NUKTA[c] ?: roman; step = 2 }
                    units += Cons(roman, null); k += step; continue
                }
                c in PRECOMPOSED_NUKTA -> units += Cons(PRECOMPOSED_NUKTA.getValue(c), null)
                c in VOWELS -> units += Vowel(VOWELS.getValue(c))
                c in MATRAS -> (units.lastOrNull() as? Cons)?.let { it.vowel = MATRAS.getValue(c) } ?: units.add(Vowel(MATRAS.getValue(c)))
                c == '्' -> (units.lastOrNull() as? Cons)?.let { it.vowel = "" }                 // virama
                c == 'ं' || c == 'ँ' -> when (val last = units.lastOrNull()) {               // anusvara, chandrabindu
                    is Cons -> last.nasal = true
                    is Vowel -> last.nasal = true
                    else -> units += Other("n")
                }
                c == 'ः' -> units += Other("h")                                                   // visarga
                c == 'ऽ' -> {}                                                                  // avagraha: silent
                c == 'ॐ' -> units += Other("om")
                else -> units += Other("")
            }
            k++
        }
        deleteSchwas(units)
        val sb = StringBuilder()
        units.forEachIndexed { idx, u ->
            val last = idx == units.lastIndex
            when (u) {
                is Cons -> {
                    sb.append(u.roman)
                    val v = u.vowel ?: "a"
                    sb.append(finalVowel(v, last, u.nasal))
                    if (u.nasal) sb.append(nasalLetter(units.getOrNull(idx + 1)))
                }
                is Vowel -> {
                    sb.append(if (idx == 0 && units.size == 1) u.roman else finalVowel(u.roman, last, u.nasal))
                    if (u.nasal) sb.append(nasalLetter(units.getOrNull(idx + 1)))
                }
                is Other -> sb.append(u.roman)
            }
        }
        return sb.toString()
    }

    /** Silent inherent "a": always at the end of a longer word, and in V-C(a)-CV patterns (scanned right to left). */
    private fun deleteSchwas(units: List<Piece>) {
        val cons = units
        val lastCons = cons.lastOrNull()
        if (cons.size > 1 && lastCons is Cons && lastCons.vowel == null && !lastCons.nasal) lastCons.vowel = ""
        for (i in cons.lastIndex - 1 downTo 1) {
            val u = cons[i] as? Cons ?: continue
            if (u.vowel != null || u.nasal) continue
            val prev = cons[i - 1]
            val next = cons[i + 1]
            val prevHasVowel = when (prev) { is Cons -> prev.vowel != ""; is Vowel -> true; else -> false }
            val nextHasVowel = when (next) { is Cons -> next.vowel != ""; is Vowel -> true; else -> false }
            if (prevHasVowel && next is Cons && nextHasVowel) u.vowel = ""
        }
    }

    private fun finalVowel(v: String, last: Boolean, nasal: Boolean): String {
        if (!last) return v
        return when (v) {
            "aa" -> if (nasal) "aa" else "a"
            "ee" -> "i"
            "oo" -> if (nasal) "oo" else "u"
            "e" -> if (nasal) "ei" else "e"
            else -> v
        }
    }

    /** Nasal sound: "m" before p/b/m (संबंध → sambandh), otherwise "n". */
    private fun nasalLetter(next: Piece?): String {
        val r = (next as? Cons)?.roman ?: return "n"
        return if (r.startsWith("p") || r.startsWith("b") || r.startsWith("m")) "m" else "n"
    }

    private fun capitalizeSentences(s: String): String {
        val sb = StringBuilder(s)
        var start = true
        for (i in sb.indices) {
            val ch = sb[i]
            if (start && ch.isLetterOrDigit()) { sb.setCharAt(i, ch.uppercaseChar()); start = false }
            else if (ch == '.' || ch == '?' || ch == '!') start = true
        }
        return sb.toString()
    }

    private fun isDevanagariLetter(c: Char) = c in 'ऀ'..'ॣ' || c in '॰'..'ॿ'

    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'ऩ' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ऱ' to "r", 'ल' to "l", 'ळ' to "l", 'ऴ' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
    )
    private val NUKTA = mapOf('क' to "q", 'ख' to "kh", 'ग' to "gh", 'ज' to "z", 'ड' to "d", 'ढ' to "dh", 'फ' to "f", 'य' to "y")
    private val PRECOMPOSED_NUKTA = mapOf(
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "gh", '\u095B' to "z", '\u095C' to "d", '\u095D' to "dh", '\u095E' to "f", '\u095F' to "y",
    )
    private val VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ऍ' to "e", 'ए' to "e", 'ऐ' to "ai", 'ऑ' to "o", 'ओ' to "o", 'औ' to "au",
    )
    private val MATRAS = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'ॅ' to "e", 'े' to "e", 'ै' to "ai", 'ॉ' to "o", 'ो' to "o", 'ौ' to "au",
    )
}
