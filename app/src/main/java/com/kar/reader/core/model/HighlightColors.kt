package com.kar.reader.core.model

/** Highlight colours. Keys are stored in the database, so never rename them. */
enum class HighlightColor(val argb: Long, val defaultMeaning: String) {
    YELLOW(0xFFF2C744, "Key idea"),
    RED(0xFFE5735F, "Question"),
    BLUE(0xFF6FA3E8, "Evidence"),
    GREEN(0xFF79C08A, "Quote to use"),
    PURPLE(0xFFB58BD6, "Vocabulary");

    /** CSS colour with transparency for EPUB rendering. */
    val css: String
        get() {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return "rgba($r,$g,$b,0.42)"
        }

    companion object {
        fun fromKey(key: String?): HighlightColor = entries.firstOrNull { it.name == key } ?: YELLOW
    }
}

/** Ink tools for PDF annotation and notebooks. */
enum class InkTool { PEN, HIGHLIGHTER }

/** Ink palette shown in the toolbar. */
val InkPalette: List<Long> = listOf(0xFFC0392B, 0xFF1E5AA8, 0xFF1A2130, 0xFF2E7D32, 0xFFF2C744)
