package com.kar.reader.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The authoritative store for user-created data. Schema JSON is exported to app/schemas so every
 * future version ships an explicit, tested migration. No destructive fallback is configured.
 *
 * Version 1 is the first released schema (0.1.0 was never distributed, so it needs no migration).
 * Version 2 adds transcripts; it only creates new tables, so Room generates the migration from the exported
 * schemas (app/schemas) and MigrationTest checks it.
 */
@Database(
    entities = [
        ProfileEntity::class,
        BookEntity::class,
        BookStateEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        HighlightEntity::class,
        NoteEntity::class,
        NoteVersionEntity::class,
        InkStrokeEntity::class,
        ReaderPreferencesEntity::class,
        TagEntity::class,
        BookTagEntity::class,
        CollectionEntity::class,
        CollectionBookEntity::class,
        ColorMeaningEntity::class,
        FlashcardEntity::class,
        VocabularyEntity::class,
        ReadingSessionEntity::class,
        PageTextEntity::class,
        NotebookEntity::class,
        NotebookPageEntity::class,
        NotebookStrokeEntity::class,
        TranscriptEntity::class,
        TranscriptSegmentEntity::class,
        TranscriptSpeakerEntity::class,
        TranscriptMarkEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class KarDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun bookDao(): BookDao
    abstract fun bookStateDao(): BookStateDao
    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun noteDao(): NoteDao
    abstract fun noteVersionDao(): NoteVersionDao
    abstract fun inkDao(): InkDao
    abstract fun readerPreferencesDao(): ReaderPreferencesDao
    abstract fun tagDao(): TagDao
    abstract fun collectionDao(): CollectionDao
    abstract fun colorMeaningDao(): ColorMeaningDao
    abstract fun flashcardDao(): FlashcardDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun sessionDao(): SessionDao
    abstract fun pageTextDao(): PageTextDao
    abstract fun notebookDao(): NotebookDao
    abstract fun transcriptDao(): TranscriptDao

    companion object {
        const val NAME = "kar_reader.db"
    }
}
