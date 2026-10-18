package com.kar.reader.di

import android.content.Context
import androidx.room.Room
import com.kar.reader.data.db.BookDao
import com.kar.reader.data.db.BookmarkDao
import com.kar.reader.data.db.InkDao
import com.kar.reader.data.db.KarDatabase
import com.kar.reader.data.db.ProgressDao
import com.kar.reader.data.db.ReaderPreferencesDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KarDatabase =
        Room.databaseBuilder(context, KarDatabase::class.java, KarDatabase.NAME)
            // Deliberately no fallbackToDestructiveMigration(): user data must never be silently dropped.
            .build()

    @Provides fun bookDao(db: KarDatabase): BookDao = db.bookDao()
    @Provides fun progressDao(db: KarDatabase): ProgressDao = db.progressDao()
    @Provides fun bookmarkDao(db: KarDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun inkDao(db: KarDatabase): InkDao = db.inkDao()
    @Provides fun readerPreferencesDao(db: KarDatabase): ReaderPreferencesDao = db.readerPreferencesDao()
}
