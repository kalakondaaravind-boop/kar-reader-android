package com.kar.reader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The authoritative store for user-created data. Schema JSON is exported to app/schemas so every
 * future version ships an explicit, tested migration (no destructive fallback is configured).
 */
@Database(
    entities = [
        BookEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        InkAnnotationEntity::class,
        ReaderPreferencesEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class KarDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun inkDao(): InkDao
    abstract fun readerPreferencesDao(): ReaderPreferencesDao

    companion object {
        const val NAME = "kar_reader.db"
    }
}
