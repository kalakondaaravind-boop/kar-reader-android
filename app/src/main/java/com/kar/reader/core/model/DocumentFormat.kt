package com.kar.reader.core.model

/** Document formats KAR READER can open. Stored by name in the database, so never rename entries. */
enum class DocumentFormat(val mimeType: String, val extension: String) {
    PDF("application/pdf", "pdf"),
    EPUB("application/epub+zip", "epub");

    companion object {
        fun fromNameOrNull(name: String?): DocumentFormat? = entries.firstOrNull { it.name == name }
    }
}
