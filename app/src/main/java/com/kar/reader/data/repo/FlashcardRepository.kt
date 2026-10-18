package com.kar.reader.data.repo

import com.kar.reader.core.domain.CardSchedule
import com.kar.reader.core.domain.ReviewGrade
import com.kar.reader.core.domain.SpacedRepetition
import com.kar.reader.data.db.FlashcardDao
import com.kar.reader.data.db.FlashcardEntity
import com.kar.reader.data.db.HighlightEntity
import com.kar.reader.data.db.VocabularyDao
import com.kar.reader.data.db.VocabularyEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Flashcards made from highlights and saved words, scheduled with spaced repetition. All offline. */
@Singleton
class FlashcardRepository @Inject constructor(
    private val cardDao: FlashcardDao,
    private val vocabDao: VocabularyDao,
    private val widgets: WidgetRefresher,
) {
    fun observeCards(profileId: String): Flow<List<FlashcardEntity>> = cardDao.observeForProfile(profileId)

    suspend fun countDue(profileId: String, now: Long = System.currentTimeMillis()): Int = cardDao.countDue(profileId, now)

    /** Turns a highlight into a fill-in-the-gap card. Very short highlights are skipped. */
    suspend fun createFromHighlight(h: HighlightEntity) {
        if (cardDao.countForSource(h.id) > 0) return
        val (prompt, answer) = SpacedRepetition.cloze(h.text) ?: return
        if (h.text.length < 12) return
        val now = System.currentTimeMillis()
        cardDao.insert(
            FlashcardEntity(
                id = UUID.randomUUID().toString(),
                profileId = h.profileId,
                sourceType = "HIGHLIGHT",
                sourceId = h.id,
                bookId = h.bookId,
                front = prompt,
                back = answer,
                dueAt = now,
                createdAt = now,
            ),
        )
        widgets.requestRefresh()
    }

    suspend fun review(card: FlashcardEntity, grade: ReviewGrade, now: Long = System.currentTimeMillis()) {
        val next = SpacedRepetition.review(card.schedule(), grade, now)
        cardDao.upsert(
            card.copy(dueAt = next.dueAt, intervalMinutes = next.intervalMinutes, ease = next.ease, reps = next.reps, lapses = next.lapses, modifiedAt = now),
        )
        widgets.requestRefresh()
    }

    fun preview(card: FlashcardEntity, grade: ReviewGrade, now: Long): String = SpacedRepetition.preview(card.schedule(), grade, now)

    suspend fun softDeleteForSource(sourceId: String, now: Long) = cardDao.softDeleteForSource(sourceId, now)

    suspend fun restoreForSource(sourceId: String, now: Long) = cardDao.restoreForSource(sourceId, now)

    // ---- Vocabulary ----------------------------------------------------------------------------

    fun observeVocabulary(profileId: String): Flow<List<VocabularyEntity>> = vocabDao.observeForProfile(profileId)

    suspend fun saveWord(profileId: String, word: String, context: String, bookId: String?, note: String = ""): VocabularyEntity {
        val now = System.currentTimeMillis()
        val clean = word.trim().trim('.', ',', ';', ':', '!', '?', '"', '“', '”').take(80)
        val entry = VocabularyEntity(UUID.randomUUID().toString(), profileId, clean, note, context.take(400), bookId, now)
        vocabDao.insert(entry)
        cardDao.insert(
            FlashcardEntity(
                id = UUID.randomUUID().toString(),
                profileId = profileId,
                sourceType = "VOCABULARY",
                sourceId = entry.id,
                bookId = bookId,
                front = clean,
                back = note.ifBlank { context.ifBlank { "Recall what this word means in context." } },
                dueAt = now,
                createdAt = now,
            ),
        )
        widgets.requestRefresh()
        return entry
    }

    suspend fun updateWordNote(entry: VocabularyEntity, note: String) =
        vocabDao.upsert(entry.copy(note = note.take(400), modifiedAt = System.currentTimeMillis()))

    suspend fun deleteWord(id: String) {
        val now = System.currentTimeMillis()
        vocabDao.softDelete(id, now)
        cardDao.softDeleteForSource(id, now)
    }

    suspend fun restoreWord(id: String) {
        val now = System.currentTimeMillis()
        vocabDao.restore(id, now)
        cardDao.restoreForSource(id, now)
    }

    private fun FlashcardEntity.schedule() = CardSchedule(dueAt, intervalMinutes, ease, reps, lapses)
}
