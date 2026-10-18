package com.kar.reader.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A document in the library. The file itself lives in app-managed storage at [storedFileName].
 * Extracted metadata ([title], [author]) is kept separate from user overrides ([userTitle], [userAuthor]).
 * [contentHash] (SHA-256) identifies the exact document version and drives duplicate detection.
 */
@Entity(
    tableName = "books",
    indices = [Index(value = ["content_hash"], unique = true), Index("last_opened_at")],
)
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
    @ColumnInfo(name = "language") val language: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long? = null,
    val favorite: Boolean = false,
)

val BookEntity.displayTitle: String get() = userTitle?.takeIf { it.isNotBlank() } ?: title
val BookEntity.displayAuthor: String? get() = userAuthor?.takeIf { it.isNotBlank() } ?: author

/**
 * Last reading position. [position] is a 0-based page (PDF) or spine index (EPUB);
 * [progression] is the 0..1 offset within it; [totalProgression] is 0..1 across the document.
 */
@Entity(
    tableName = "reading_progress",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
)
data class ReadingProgressEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    val position: Int,
    val progression: Float,
    @ColumnInfo(name = "total_progression") val totalProgression: Float,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("book_id")],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val position: Int,
    val progression: Float,
    val label: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * A freehand ink stroke on a PDF page. Points are normalized to 0..1 of the unrotated page size,
 * so strokes stay aligned at any zoom level or screen size. [documentHash] records the document
 * version the stroke was drawn on, so a changed source can be detected.
 */
@Entity(
    tableName = "ink_annotations",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["book_id", "page_index"])],
)
data class InkAnnotationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    @ColumnInfo(name = "color_argb") val colorArgb: Int,
    /** Stroke width as a fraction of page width. */
    @ColumnInfo(name = "width_fraction") val widthFraction: Float,
    /** Encoded points, see [com.kar.reader.feature.pdf.InkPoints]. */
    val points: String,
    @ColumnInfo(name = "document_hash") val documentHash: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Per-book EPUB reading preferences. */
@Entity(
    tableName = "reader_preferences",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
)
data class ReaderPreferencesEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "font_scale_percent") val fontScalePercent: Int = 100,
    @ColumnInfo(name = "line_height") val lineHeight: Float = 1.5f,
    @ColumnInfo(name = "margin_em") val marginEm: Float = 1.0f,
    val theme: String = "LIGHT",
)
