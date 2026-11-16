package com.kar.reader.core.domain

/** Languages the bundled multilingual Whisper model can transcribe (code to English name). */
object WhisperLanguages {
    val all: List<Pair<String, String>> = listOf(
        "en" to "English", "hi" to "Hindi", "te" to "Telugu", "ta" to "Tamil", "kn" to "Kannada", "ml" to "Malayalam",
        "mr" to "Marathi", "bn" to "Bengali", "gu" to "Gujarati", "pa" to "Punjabi", "ur" to "Urdu", "ne" to "Nepali",
        "si" to "Sinhala", "as" to "Assamese", "sa" to "Sanskrit", "sd" to "Sindhi",
        "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean", "es" to "Spanish", "fr" to "French", "de" to "German",
        "pt" to "Portuguese", "it" to "Italian", "ru" to "Russian", "ar" to "Arabic", "tr" to "Turkish", "pl" to "Polish",
        "nl" to "Dutch", "sv" to "Swedish", "id" to "Indonesian", "ms" to "Malay", "vi" to "Vietnamese", "th" to "Thai",
        "fa" to "Persian", "he" to "Hebrew", "uk" to "Ukrainian", "el" to "Greek", "cs" to "Czech", "ro" to "Romanian",
        "hu" to "Hungarian", "fi" to "Finnish", "da" to "Danish", "no" to "Norwegian", "nn" to "Norwegian Nynorsk",
        "ca" to "Catalan", "hr" to "Croatian", "sr" to "Serbian", "bs" to "Bosnian", "sl" to "Slovenian", "sk" to "Slovak",
        "bg" to "Bulgarian", "mk" to "Macedonian", "lt" to "Lithuanian", "lv" to "Latvian", "et" to "Estonian",
        "is" to "Icelandic", "cy" to "Welsh", "br" to "Breton", "eu" to "Basque", "gl" to "Galician",
        "oc" to "Occitan", "lb" to "Luxembourgish", "fo" to "Faroese", "mt" to "Maltese", "sq" to "Albanian",
        "hy" to "Armenian", "ka" to "Georgian", "az" to "Azerbaijani", "kk" to "Kazakh", "uz" to "Uzbek", "tk" to "Turkmen",
        "tg" to "Tajik", "mn" to "Mongolian", "ba" to "Bashkir", "tt" to "Tatar", "be" to "Belarusian", "yi" to "Yiddish",
        "la" to "Latin", "ps" to "Pashto", "km" to "Khmer", "lo" to "Lao", "my" to "Myanmar", "bo" to "Tibetan",
        "tl" to "Tagalog", "jw" to "Javanese", "su" to "Sundanese", "mi" to "Maori", "haw" to "Hawaiian",
        "sw" to "Swahili", "am" to "Amharic", "so" to "Somali", "yo" to "Yoruba", "ha" to "Hausa", "sn" to "Shona",
        "ln" to "Lingala", "mg" to "Malagasy", "af" to "Afrikaans", "ht" to "Haitian Creole",
    )

    private val byCode = all.toMap()

    fun name(code: String): String = byCode[code] ?: code.uppercase()

    /** "en,hi" → "English + Hindi". */
    fun describe(codes: String): String = codes.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" + ") { name(it) }

    /** Languages that cover at least [minShare] of the recognized chunks, most common first (at most three). */
    fun summarize(perChunk: List<String>, minShare: Double = 0.1): String {
        val counted = perChunk.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        val total = counted.values.sum().coerceAtLeast(1)
        return counted.entries.sortedByDescending { it.value }.filter { it.value.toDouble() / total >= minShare }.take(3).joinToString(",") { it.key }
    }
}
