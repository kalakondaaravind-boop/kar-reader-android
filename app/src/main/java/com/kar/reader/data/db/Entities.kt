package com.kar.reader.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kar.reader.core.domain.Syncable
import kotlinx.serialization.Serializable
import androidx.room.Ignore

/*
 * Schema notes
 * - Every entity is @Serializable so backups and sync snapshots are produced from the same classes.
 * - User data is scoped by profile_id (family profiles). Books and their files are shared by all profiles.
 * - Rows the user can delete carry deleted_at (recycle bin); they are purged after 30 days.
 * - modified_at drives sync merging (newest change wins; content conflicts are kept in note history).
 */

@Serializable
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "color_index") val colorIndex: Int,
    @ColumnInfo(name = "pin_hash") val pinHash: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * A document in the shared library. The file lives in app-managed storage at [storedFileName].
 * Extracted metadata ([title], [author]) is kept separate from user overrides ([userTitle], [userAuthor]).
 * [contentHash] (SHA-256) identifies the exact document version and drives duplicate detection and sync.
 */
@Serializable
@Entity(tableName = "books", indices = [Index(value = ["content_hash"], unique = true), Index("deleted_at")])
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    @ColumnInfo(name = "user_title") val userTitle: String? = null,
    @ColumnInfo(name = "user_author") val userAuthor: String? = null,
    val format: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "stored_file_name") val storedFileName: String,
    @ColumnInfo(name = "source_display_name") val sourceDisplayName: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "page_count") val pageCount: Int?,
    val language: String? = null,
    val year: String? = null,
    val publisher: String? = null,
    @ColumnInfo(name = "series_name") val seriesName: String? = null,
    @ColumnInfo(name = "series_index") val seriesIndex: Double? = null,
    val rating: Int = 0,
    /** IMPORT, ZIP, SCAN or CALIBRE. */
    val source: String = "IMPORT",
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long = addedAt,
)

val BookEntity.displayTitle: String get() = userTitle?.takeIf { it.isNotBlank() } ?: title
val BookEntity.displayAuthor: String? get() = userAuthor?.takeIf { it.isNotBlank() } ?: author

/** Per-profile state of a book: favorite, private, last opened, finished. */
@Serializable
@Entity(
    tableName = "book_states",
    primaryKeys = ["profile_id", "book_id"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("book_id")],
)
data class BookStateEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val favorite: Boolean = false,
    @ColumnInfo(name = "is_private") val isPrivate: Boolean = false,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long? = null,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long = 0,
)

/**
 * Last reading position. [position] is a 0-based page (PDF) or spine index (EPUB);
 * [progression] is the 0..1 offset within it; [totalProgression] is 0..1 across the document.
 */
@Serializable
@Entity(
    tableName = "reading_progress",
    primaryKeys = ["profile_id", "book_id"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("book_id")],
)
data class ReadingProgressEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val position: Int,
    val progression: Float,
    @ColumnInfo(name = "total_progression") val totalProgression: Float,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("book_id"), Index("profile_id")],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val position: Int,
    val progression: Float,
    val label: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "modified_at") override val modifiedAt: Long = createdAt,
) : Syncable {
    @get:Ignore override val syncId: String get() = id
}

/**
 * A text highlight. EPUB: [locator] is "start:length" in the chapter's text content. PDF: [locator]
 * is a normalized rectangle "l,t,r,b" and [text] comes from on-device text recognition of that area.
 * [colorKey] names a highlight colour whose meaning is defined per profile.
 */
@Serializable
@Entity(
    tableName = "highlights",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("book_id"), Index("profile_id")],
)
data class HighlightEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val position: Int,
    val locator: String,
    val text: String,
    @ColumnInfo(name = "color_key") val colorKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "modified_at") override val modifiedAt: Long = createdAt,
) : Syncable {
    @get:Ignore override val syncId: String get() = id
}

@Serializable
@Entity(
    tableName = "notes",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("book_id"), Index("profile_id"), Index("highlight_id")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "book_id") val bookId: String? = null,
    @ColumnInfo(name = "highlight_id") val highlightId: String? = null,
    val position: Int? = null,
    val title: String,
    val body: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "modified_at") override val modifiedAt: Long = createdAt,
) : Syncable {
    @get:Ignore override val syncId: String get() = id
}

/** Earlier versions of a note. Saving, restoring and sync conflicts each add a version, so nothing is lost. */
@Serializable
@Entity(
    tableName = "note_versions",
    foreignKeys = [ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["note_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("note_id")],
)
data class NoteVersionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    /** EDIT, RESTORE or SYNC. */
    val origin: String,
)

/**
 * A freehand stroke on a PDF page. Points are normalized to 0..1 of the unrotated page, so strokes
 * stay aligned at any zoom. [documentHash] records the document version the stroke was drawn on.
 */
@Serializable
@Entity(
    tableName = "ink_strokes",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["book_id", "page_index"]), Index("profile_id")],
)
data class InkStrokeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    /** PEN or HIGHLIGHTER. */
    val tool: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Int,
    @ColumnInfo(name = "width_fraction") val widthFraction: Float,
    val points: String,
    @ColumnInfo(name = "document_hash") val documentHash: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "modified_at") override val modifiedAt: Long = createdAt,
) : Syncable {
    @get:Ignore override val syncId: String get() = id
}

@Serializable
@Entity(
    tableName = "reader_preferences",
    primaryKeys = ["profile_id", "book_id"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("book_id")],
)
data class ReaderPreferencesEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "font_scale_percent") val fontScalePercent: Int = 100,
    @ColumnInfo(name = "line_height") val lineHeight: Float = 1.6f,
    @ColumnInfo(name = "margin_em") val marginEm: Float = 1.2f,
    val theme: String = "LIGHT",
)

@Serializable
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(@PrimaryKey val id: String, val name: String)

@Serializable
@Entity(
    tableName = "book_tags",
    primaryKeys = ["book_id", "tag_id"],
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tag_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tag_id")],
)
data class BookTagEntity(@ColumnInfo(name = "book_id") val bookId: String, @ColumnInfo(name = "tag_id") val tagId: String)

/** A shelf. With [smartQuery] set (see SmartQuery.encode) it fills itself from rules. */
@Serializable
@Entity(tableName = "collections", indices = [Index("profile_id")])
data class CollectionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    val name: String,
    @ColumnInfo(name = "smart_query") val smartQuery: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") override val modifiedAt: Long = createdAt,
) : Syncable {
    @get:Ignore override val syncId: String get() = id
}

@Serializable
@Entity(
    tableName = "collection_books",
    primaryKeys = ["collection_id", "book_id"],
    foreignKeys = [
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collection_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("book_id")],
)
data class CollectionBookEntity(@ColumnInfo(name = "collection_id") val collectionId: String, @ColumnInfo(name = "book_id") val bookId: String)

/** What each highlight colour means to a profile, e.g. YELLOW = "Key idea". */
@Serializable
@Entity(tableName = "color_meanings", primaryKeys = ["profile_id", "color_key"])
data class ColorMeaningEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "color_key") val colorKey: String,
    val label: String,
)

@Serializable
@Entity(tableName = "flashcards", indices = [Index("profile_id"), Index("due_at"), Index("source_id")])
data class FlashcardEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    /** HIGHLIGHT or VOCABULARY. */
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "book_id") val bookId: String?,
    val front: String,
    val back: String,
    @ColumnInfo(name = "due_at") val dueAt: Long,
    @ColumnInfo(name = "interval_minutes") val intervalMinutes: Long = 0,
    val ease: Double = 2.5,
    val reps: Int = 0,
    val lapses: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "modified_at") override val modifiedAt: Long = createdAt,
) : Syncable {
    @get:Ignore override val syncId: String get() = id
}

@Serializable
@Entity(tableName = "vocabulary", indices = [Index("profile_id")])
data class VocabularyEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    val word: String,
    val note: String = "",
    val context: String = "",
    @ColumnInfo(name = "book_id") val bookId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "modified_at") override val modifiedAt: Long = createdAt,
) : Syncable {
    @get:Ignore override val syncId: String get() = id
}

/** A measured reading session. Only time the reader was actually on screen is recorded. */
@Serializable
@Entity(tableName = "reading_sessions", indices = [Index("profile_id"), Index("started_at")])
data class ReadingSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "book_id") val bookId: String?,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val focus: Boolean = false,
    @ColumnInfo(name = "modified_at") override val modifiedAt: Long = startedAt,
) : Syncable {
    @get:Ignore override val syncId: String get() = id
}

/** Recognized text of a scanned page, kept so scanned documents can be cited and searched. */
@Serializable
@Entity(
    tableName = "page_text",
    primaryKeys = ["book_id", "page"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
)
data class PageTextEntity(@ColumnInfo(name = "book_id") val bookId: String, val page: Int, val text: String)

@Serializable
@Entity(tableName = "notebooks", indices = [Index("profile_id")])
data class NotebookEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "modified_at") override val modifiedAt: Long = createdAt,
) : Syncable {
    @get:Ignore override val syncId: String get() = id
}

@Serializable
@Entity(
    tableName = "notebook_pages",
    foreignKeys = [ForeignKey(entity = NotebookEntity::class, parentColumns = ["id"], childColumns = ["notebook_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("notebook_id")],
)
data class NotebookPageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "notebook_id") val notebookId: String,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    /** BLANK, LINED, GRID or DOTTED. */
    val template: String,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
)

/** A notebook stroke. Points are normalized to 0..1 of the page width (y uses the same scale). */
@Serializable
@Entity(
    tableName = "notebook_strokes",
    foreignKeys = [ForeignKey(entity = NotebookPageEntity::class, parentColumns = ["id"], childColumns = ["page_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("page_id")],
)
data class NotebookStrokeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "page_id") val pageId: String,
    val tool: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Int,
    @ColumnInfo(name = "width_fraction") val widthFraction: Float,
    val points: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long = createdAt,
)
