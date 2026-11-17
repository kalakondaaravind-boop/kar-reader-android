package com.kar.reader.desktop

import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val id: String,
    val hash: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val year: String? = null,
    val publisher: String? = null,
    val format: String,            // "PDF" or "EPUB"
    val file: String,              // stored file name inside books/
    val addedAt: Long,
    val lastOpenedAt: Long = 0,
    val position: Int = 0,         // PDF page index, or EPUB chapter index
    val scroll: Float = 0f,        // fraction within that page or chapter
    val units: Int = 0,            // number of pages or chapters
    val status: String = "unread", // unread | reading | finished
    val finishedAt: Long = 0,
    val rating: Int = 0,
    val tags: List<String> = emptyList(),
    val deletedAt: Long = 0,
) {
    val isPdf get() = format == "PDF"
    val progress: Float get() = if (units <= 1) (if (status == "finished") 1f else 0f) else ((position + scroll) / units).coerceIn(0f, 1f)
    val authorLine get() = authors.joinToString(", ")
}

@Serializable
data class Highlight(
    val id: String,
    val bookId: String,
    val color: String = "YELLOW",
    val text: String,
    val note: String = "",
    val label: String,             // "p. 12" or chapter title
    val unit: Int,                 // PDF page index or EPUB chapter index
    val start: Int = -1,           // EPUB character range (-1 for PDF)
    val end: Int = -1,
    val rx: Float = 0f, val ry: Float = 0f, val rw: Float = 0f, val rh: Float = 0f, // PDF area as fractions of the page
    val createdAt: Long,
) { val isArea get() = rw > 0f && rh > 0f }

@Serializable
data class Bookmark(val id: String, val bookId: String, val unit: Int, val scroll: Float, val label: String, val createdAt: Long)

@Serializable
data class Note(val id: String, val bookId: String? = null, val title: String, val body: String, val updatedAt: Long)

@Serializable
data class Card(
    val id: String,
    val bookId: String? = null,
    val front: String,
    val back: String,
    val dueAt: Long,
    val intervalMinutes: Long = 0,
    val ease: Double = 2.5,
    val reps: Int = 0,
    val lapses: Int = 0,
)

@Serializable
data class Session(val bookId: String, val startedAt: Long, val durationMs: Long, val focus: Boolean = false)

@Serializable
data class TranscriptMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val audio: String,             // file name inside recordings/
    val language: String,          // "" automatic, or a Whisper language code
    val languages: String = "",    // what was actually spoken, for display
    val speakerNames: Map<Int, String> = emptyMap(),
    val status: String = "queued", // queued | working | done | failed
    val error: String = "",
    val bookId: String? = null,
    val deletedAt: Long = 0,
    val separate: Boolean = true,  // tell speakers apart
    val speakers: Int = 0,         // expected number of speakers, 0 = find out
    val startedAt: Long = 0,       // when a live recording began (0 for imported audio)
    val meeting: Boolean = false,  // recorded as a desktop meeting (microphone + what the computer plays)
)

@Serializable
data class Line(val startMs: Long, val endMs: Long, val speaker: Int, val text: String)

@Serializable
data class Settings(
    val theme: String = "system",       // system | light | dark
    val citation: String = "APA",
    val hindiRoman: Boolean = false,    // show Hindi in Roman letters (Hinglish) instead of Devanagari
    val epubFont: Int = 18,
    val epubSerif: Boolean = true,
    val removeFillers: Boolean = false,
    val ttsRate: Int = 0,               // Windows speech rate, -10..10
    val msClientId: String = "",        // Azure app registration for the Teams calendar (optional)
    val msTenant: String = "organizations",
)

@Serializable
data class Data(
    val books: List<Book> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val notes: List<Note> = emptyList(),
    val cards: List<Card> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val transcripts: List<TranscriptMeta> = emptyList(),
    val minutes: List<Minutes> = emptyList(),
    val settings: Settings = Settings(),
)
