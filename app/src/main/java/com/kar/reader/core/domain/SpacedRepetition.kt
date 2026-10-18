package com.kar.reader.core.domain

import kotlin.math.max
import kotlin.math.roundToLong

enum class ReviewGrade { AGAIN, HARD, GOOD, EASY }

/** Scheduling state of one flashcard. Times are epoch milliseconds. */
data class CardSchedule(
    val dueAt: Long,
    val intervalMinutes: Long = 0,
    val ease: Double = 2.5,
    val reps: Int = 0,
    val lapses: Int = 0,
) {
    val isNew: Boolean get() = reps == 0
}

/**
 * A small SM-2 variant (the algorithm family used by Anki and SuperMemo):
 * new or relearning cards step through minutes; graduated cards grow by their ease factor.
 */
object SpacedRepetition {
    const val MINUTE = 60_000L
    private const val DAY_MIN = 24 * 60L
    private const val MIN_EASE = 1.3
    private const val MAX_INTERVAL_MIN = 365 * DAY_MIN * 3

    fun newCard(now: Long) = CardSchedule(dueAt = now)

    fun review(card: CardSchedule, grade: ReviewGrade, now: Long): CardSchedule {
        val learning = card.intervalMinutes < DAY_MIN
        val next: Long
        var ease = card.ease
        var lapses = card.lapses
        when (grade) {
            ReviewGrade.AGAIN -> {
                next = 1
                if (!learning) { lapses++; ease -= 0.2 }
            }
            ReviewGrade.HARD -> {
                next = if (learning) 10 else max(DAY_MIN, (card.intervalMinutes * 1.2).roundToLong())
                ease -= 0.15
            }
            ReviewGrade.GOOD -> {
                next = if (learning) DAY_MIN else (card.intervalMinutes * ease).roundToLong()
            }
            ReviewGrade.EASY -> {
                next = if (learning) 4 * DAY_MIN else (card.intervalMinutes * ease * 1.3).roundToLong()
                ease += 0.15
            }
        }
        val interval = next.coerceIn(1, MAX_INTERVAL_MIN)
        return card.copy(
            dueAt = now + interval * MINUTE,
            intervalMinutes = interval,
            ease = ease.coerceAtLeast(MIN_EASE),
            reps = card.reps + 1,
            lapses = lapses,
        )
    }

    /** Human label for the interval each button would give, e.g. "10 min", "4 days". */
    fun preview(card: CardSchedule, grade: ReviewGrade, now: Long): String =
        formatInterval(review(card, grade, now).intervalMinutes)

    fun formatInterval(minutes: Long): String = when {
        minutes < 60 -> "$minutes min"
        minutes < DAY_MIN -> "${minutes / 60} h"
        minutes < 30 * DAY_MIN -> { val d = minutes / DAY_MIN; if (d == 1L) "1 day" else "$d days" }
        minutes < 365 * DAY_MIN -> { val m = minutes / (30 * DAY_MIN); if (m == 1L) "1 month" else "$m months" }
        else -> { val y = minutes / (365 * DAY_MIN); if (y == 1L) "1 year" else "$y years" }
    }

    /** Builds a fill-in-the-gap prompt by hiding the longest word of a highlight. */
    fun cloze(text: String): Pair<String, String>? {
        val words = Regex("[\\p{L}][\\p{L}'’-]{3,}").findAll(text).toList()
        val target = words.maxByOrNull { it.value.length } ?: return null
        val prompt = text.substring(0, target.range.first) + "_____" + text.substring(target.range.last + 1)
        return prompt to target.value
    }
}
