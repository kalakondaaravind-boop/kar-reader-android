package com.kar.reader.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class RetentionAndSyncTest {

    private val day = 86_400_000L

    @Test
    fun trashPolicy() {
        val now = 100 * day
        assertEquals(30, TrashPolicy.daysLeft(now, now))
        assertEquals(28, TrashPolicy.daysLeft(now - 2 * day, now))
        assertEquals(0, TrashPolicy.daysLeft(now - 40 * day, now))
        assertTrue(TrashPolicy.shouldPurge(now - 31 * day, now))
        assertFalse(TrashPolicy.shouldPurge(now - 29 * day, now))
    }

    @Test
    fun streaksAndRecap() {
        val zone = ZoneOffset.UTC
        val d0 = LocalDate.of(2026, 10, 18)
        fun at(d: LocalDate, minutes: Long, book: String = "a") =
            SessionFact(book, d.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000, minutes * 60_000, false)
        val sessions = listOf(at(d0, 10), at(d0.minusDays(1), 20), at(d0.minusDays(2), 5), at(d0.minusDays(5), 30), at(d0.minusDays(6), 30), at(d0.minusDays(7), 30), at(d0.minusDays(8), 30))
        assertEquals(3, ReadingStats.currentStreak(sessions, d0, zone))
        assertEquals(4, ReadingStats.bestStreak(sessions, zone))
        assertEquals(10, ReadingStats.minutesOn(d0, sessions, zone))
        val recap = ReadingStats.yearRecap(
            2026, sessions, finishedAt = listOf(at(d0, 0).startedAt),
            highlightsByBook = mapOf("a" to listOf(at(d0, 0).startedAt, at(d0, 0).startedAt), "b" to listOf(at(d0, 0).startedAt)),
            titleOf = { it.uppercase() }, zone = zone,
        )
        assertEquals(1, recap.booksFinished)
        assertEquals(3, recap.highlightsMade)
        assertEquals("A" to 2, recap.topBooks.first())
        assertEquals(155.0 / 60, recap.hoursRead, 0.001)
    }

    private data class Rec(override val syncId: String, override val modifiedAt: Long, val body: String) : Syncable

    @Test
    fun syncMergeKeepsBothVersions() {
        val local = listOf(Rec("1", 10, "a"), Rec("2", 50, "local"), Rec("3", 10, "same"))
        val remote = listOf(Rec("1", 20, "a"), Rec("2", 40, "remote"), Rec("3", 30, "same"), Rec("4", 5, "new"))
        val lww = SyncMerge.lastWriterWins(local, remote)
        assertEquals(setOf("1", "3", "4"), lww.upserts.map { it.syncId }.toSet())
        val merged = SyncMerge.withConflicts(local, remote) { a, b -> a.body == b.body }
        assertEquals(setOf("1", "3", "4"), merged.upserts.map { it.syncId }.toSet())
        assertEquals(1, merged.conflicts.size)
        assertEquals("local", merged.conflicts[0].first.body)
        assertEquals("remote", merged.conflicts[0].second.body)
    }

    @Test
    fun pairingAndPins() {
        val code = PairingCodes.generate()
        assertEquals(6, code.length)
        val sig = PairingCodes.mac(code, "hello".toByteArray())
        assertTrue(PairingCodes.verify(code, "hello".toByteArray(), sig))
        assertFalse(PairingCodes.verify("000000", "hello".toByteArray(), sig))
        val stored = PinHasher.hash("2468")
        assertTrue(PinHasher.verify("2468", stored))
        assertFalse(PinHasher.verify("1357", stored))
        assertFalse(PinHasher.verify("2468", "corrupt"))
    }
}
