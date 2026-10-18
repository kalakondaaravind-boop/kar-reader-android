package com.kar.reader.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedRepetitionTest {

    private val now = 1_000_000_000L

    @Test
    fun newCardButtonsMatchDesign() {
        val card = SpacedRepetition.newCard(now)
        assertEquals("1 min", SpacedRepetition.preview(card, ReviewGrade.AGAIN, now))
        assertEquals("10 min", SpacedRepetition.preview(card, ReviewGrade.HARD, now))
        assertEquals("1 day", SpacedRepetition.preview(card, ReviewGrade.GOOD, now))
        assertEquals("4 days", SpacedRepetition.preview(card, ReviewGrade.EASY, now))
    }

    @Test
    fun graduatedCardGrowsAndLapses() {
        var card = SpacedRepetition.review(SpacedRepetition.newCard(now), ReviewGrade.GOOD, now)
        assertEquals(24 * 60L, card.intervalMinutes)
        card = SpacedRepetition.review(card, ReviewGrade.GOOD, card.dueAt)
        assertEquals((24 * 60 * 2.5).toLong(), card.intervalMinutes)
        val lapsed = SpacedRepetition.review(card, ReviewGrade.AGAIN, card.dueAt)
        assertEquals(1L, lapsed.intervalMinutes)
        assertEquals(1, lapsed.lapses)
        assertTrue(lapsed.ease < card.ease)
    }

    @Test
    fun easeNeverDropsBelowFloor() {
        var card = SpacedRepetition.review(SpacedRepetition.newCard(now), ReviewGrade.GOOD, now)
        repeat(20) { card = SpacedRepetition.review(card, ReviewGrade.HARD, card.dueAt) }
        assertTrue(card.ease >= 1.3)
    }

    @Test
    fun clozeHidesLongestWord() {
        val (prompt, answer) = SpacedRepetition.cloze("It is a truth universally acknowledged")!!
        assertEquals("acknowledged", answer)
        assertEquals("It is a truth universally _____", prompt)
        assertNull(SpacedRepetition.cloze("a b c"))
    }
}
