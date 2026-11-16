package com.kar.reader.core.domain

/**
 * Which on-device recognizer writes each piece of a transcript.
 *
 * - English → Whisper base (punctuation and casing).
 * - Hindi and Hinglish → Omnilingual ASR, which wrote Hindi far better than Whisper base in testing (Whisper base
 *   writes Hindi in Urdu script). Its stray Urdu letters are fixed by [DevanagariCleanup].
 * - Other languages → Omnilingual ASR (1600+ languages; lowercase, no punctuation), only when the user picks that
 *   language by hand. Automatic mode recognizes English, Hindi and Hinglish only (see [automatic]).
 * When a model is missing from the build, Whisper base is used with the language set.
 */
enum class Recognizer { ENGLISH, OMNILINGUAL, WHISPER_BASE }

data class Route(val recognizer: Recognizer, val whisperLanguage: String)

object LanguageRouting {

    /** Spoken languages Whisper may report for Hindi or Hinglish speech (close relatives and neighbours). */
    private val HINDI_LIKE = setOf("hi", "ur", "ne", "mr", "pa", "sa", "sd", "gu", "bn", "as")

    /**
     * Automatic mode only distinguishes English from Hindi/Hinglish: a guess of a Hindi-like language becomes "hi",
     * any other guess becomes "en", and no guess stays "" (filled in by [smooth]).
     */
    fun automatic(detected: String): String = when {
        detected.isBlank() -> ""
        detected in HINDI_LIKE -> "hi"
        else -> "en"
    }

    fun route(language: String, hasOmnilingual: Boolean): Route = when {
        language == "en" -> Route(Recognizer.ENGLISH, "en")
        hasOmnilingual -> Route(Recognizer.OMNILINGUAL, "")
        else -> Route(Recognizer.WHISPER_BASE, if (language == "ur") "hi" else language)
    }

    /**
     * Makes per-piece language guesses consistent. Short pieces are where identification goes wrong (a few words of
     * Hindi heard as English or Urdu), so a piece shorter than [shortSec] whose language is rare for its speaker
     * (under [minShare] of that speaker's speaking time) takes the speaker's main language. Pieces with no guess take
     * the speaker's main language, or the recording's, or English.
     */
    fun smooth(spans: List<SpeechSpan>, detected: List<String>, shortSec: Float = 6f, minShare: Float = 0.25f): List<String> {
        require(spans.size == detected.size)
        val overall = dominant(spans.indices.filter { detected[it].isNotBlank() }, spans, detected)
        val bySpeaker = spans.indices.groupBy { spans[it].speaker }
        val speakerMain = bySpeaker.mapValues { (_, idx) -> dominant(idx.filter { detected[it].isNotBlank() }, spans, detected) ?: overall }
        val speakerShare = bySpeaker.mapValues { (_, idx) ->
            val total = idx.sumOf { spans[it].duration.toDouble() }.coerceAtLeast(1e-6)
            idx.filter { detected[it].isNotBlank() }.groupBy { detected[it] }.mapValues { (_, l) -> (l.sumOf { spans[it].duration.toDouble() } / total).toFloat() }
        }
        return spans.indices.map { i ->
            val s = spans[i]
            val lang = detected[i]
            val main = speakerMain[s.speaker] ?: overall ?: "en"
            when {
                lang.isBlank() -> main
                s.duration < shortSec && (speakerShare[s.speaker]?.get(lang) ?: 0f) < minShare -> main
                else -> lang
            }
        }
    }

    private fun dominant(indices: List<Int>, spans: List<SpeechSpan>, detected: List<String>): String? =
        indices.groupBy { normalize(detected[it]) }.maxByOrNull { (_, l) -> l.sumOf { spans[it].duration.toDouble() } }?.key

    /** Urdu and Hindi count as one spoken language when choosing a speaker's main language. */
    private fun normalize(code: String) = if (code == "ur") "hi" else code
}
