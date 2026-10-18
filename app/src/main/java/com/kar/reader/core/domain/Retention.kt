package com.kar.reader.core.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Recycle bin rules: deleted items are kept for [RETENTION_DAYS] days, then purged. */
object TrashPolicy {
    const val RETENTION_DAYS = 30
    private const val DAY = 86_400_000L

    fun daysLeft(deletedAt: Long, now: Long): Int {
        val elapsedDays = ((now - deletedAt).coerceAtLeast(0) / DAY).toInt()
        return (RETENTION_DAYS - elapsedDays).coerceAtLeast(0)
    }

    fun purgeBefore(now: Long): Long = now - RETENTION_DAYS * DAY

    fun shouldPurge(deletedAt: Long, now: Long): Boolean = deletedAt < purgeBefore(now)
}

/** One measured reading session. Only sessions the app actually timed are recorded. */
data class SessionFact(val bookId: String, val startedAt: Long, val durationMs: Long, val focus: Boolean)

data class YearRecap(
    val year: Int,
    val booksFinished: Int,
    val hoursRead: Double,
    val bestStreakDays: Int,
    val highlightsMade: Int,
    val topBooks: List<Pair<String, Int>>,
)

object ReadingStats {

    fun minutesOn(day: LocalDate, sessions: List<SessionFact>, zone: ZoneId): Int =
        (sessions.filter { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() == day }.sumOf { it.durationMs } / 60_000L).toInt()

    /** Consecutive days, ending today or yesterday, with at least [minMinutes] of reading. */
    fun currentStreak(sessions: List<SessionFact>, today: LocalDate, zone: ZoneId, minMinutes: Int = 1): Int {
        val days = activeDays(sessions, zone, minMinutes)
        var day = if (today in days) today else today.minusDays(1)
        var streak = 0
        while (day in days) { streak++; day = day.minusDays(1) }
        return streak
    }

    fun bestStreak(sessions: List<SessionFact>, zone: ZoneId, minMinutes: Int = 1): Int {
        val days = activeDays(sessions, zone, minMinutes).sorted()
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }
        return best
    }

    fun yearRecap(
        year: Int,
        sessions: List<SessionFact>,
        finishedAt: List<Long>,
        highlightsByBook: Map<String, List<Long>>,
        titleOf: (String) -> String,
        zone: ZoneId,
    ): YearRecap {
        fun inYear(t: Long) = Instant.ofEpochMilli(t).atZone(zone).year == year
        val yearSessions = sessions.filter { inYear(it.startedAt) }
        val counts = highlightsByBook.mapValues { (_, times) -> times.count(::inYear) }.filterValues { it > 0 }
        return YearRecap(
            year = year,
            booksFinished = finishedAt.count(::inYear),
            hoursRead = yearSessions.sumOf { it.durationMs } / 3_600_000.0,
            bestStreakDays = bestStreak(yearSessions, zone),
            highlightsMade = counts.values.sum(),
            topBooks = counts.entries.sortedByDescending { it.value }.take(3).map { titleOf(it.key) to it.value },
        )
    }

    private fun activeDays(sessions: List<SessionFact>, zone: ZoneId, minMinutes: Int): Set<LocalDate> =
        sessions.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            .filterValues { list -> list.sumOf { it.durationMs } >= minMinutes * 60_000L }
            .keys
}
