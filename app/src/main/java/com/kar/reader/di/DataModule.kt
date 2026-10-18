package com.kar.reader.di

import android.content.Context
import androidx.room.Room
import com.kar.reader.data.db.BookDao
import com.kar.reader.data.db.BookStateDao
import com.kar.reader.data.db.BookmarkDao
import com.kar.reader.data.db.CollectionDao
import com.kar.reader.data.db.ColorMeaningDao
import com.kar.reader.data.db.FlashcardDao
import com.kar.reader.data.db.HighlightDao
import com.kar.reader.data.db.InkDao
import com.kar.reader.data.db.KarDatabase
import com.kar.reader.data.db.NoteDao
import com.kar.reader.data.db.NoteVersionDao
import com.kar.reader.data.db.NotebookDao
import com.kar.reader.data.db.PageTextDao
import com.kar.reader.data.db.ProfileDao
import com.kar.reader.data.db.ProgressDao
import com.kar.reader.data.db.ReaderPreferencesDao
import com.kar.reader.data.db.SessionDao
import com.kar.reader.data.db.TagDao
import com.kar.reader.data.db.VocabularyDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
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

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides fun profileDao(db: KarDatabase): ProfileDao = db.profileDao()
    @Provides fun bookDao(db: KarDatabase): BookDao = db.bookDao()
    @Provides fun bookStateDao(db: KarDatabase): BookStateDao = db.bookStateDao()
    @Provides fun progressDao(db: KarDatabase): ProgressDao = db.progressDao()
    @Provides fun bookmarkDao(db: KarDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun highlightDao(db: KarDatabase): HighlightDao = db.highlightDao()
    @Provides fun noteDao(db: KarDatabase): NoteDao = db.noteDao()
    @Provides fun noteVersionDao(db: KarDatabase): NoteVersionDao = db.noteVersionDao()
    @Provides fun inkDao(db: KarDatabase): InkDao = db.inkDao()
    @Provides fun readerPreferencesDao(db: KarDatabase): ReaderPreferencesDao = db.readerPreferencesDao()
    @Provides fun tagDao(db: KarDatabase): TagDao = db.tagDao()
    @Provides fun collectionDao(db: KarDatabase): CollectionDao = db.collectionDao()
    @Provides fun colorMeaningDao(db: KarDatabase): ColorMeaningDao = db.colorMeaningDao()
    @Provides fun flashcardDao(db: KarDatabase): FlashcardDao = db.flashcardDao()
    @Provides fun vocabularyDao(db: KarDatabase): VocabularyDao = db.vocabularyDao()
    @Provides fun sessionDao(db: KarDatabase): SessionDao = db.sessionDao()
    @Provides fun pageTextDao(db: KarDatabase): PageTextDao = db.pageTextDao()
    @Provides fun notebookDao(db: KarDatabase): NotebookDao = db.notebookDao()
}
