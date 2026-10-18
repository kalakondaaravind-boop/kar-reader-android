package com.kar.reader.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRulesTest {

    private val day = 86_400_000L
    private val now = 100 * day

    private fun facts(format: String = "PDF", addedDaysAgo: Int = 3, progress: Int = 40, notes: Int = 0, tags: Set<String> = emptySet()) = BookFacts(
        format = format, addedAt = now - addedDaysAgo * day, lastOpenedAt = null, progressPercent = progress,
        highlightCount = 2, noteCount = notes, rating = 4, favorite = false, tags = tags, author = "Jane Austen",
    )

    private val unfinishedPdfs = SmartQuery(
        listOf(
            SmartRule(RuleField.FORMAT, RuleOp.IS, "PDF"),
            SmartRule(RuleField.ADDED_DAYS, RuleOp.WITHIN_DAYS, "30"),
            SmartRule(RuleField.PROGRESS, RuleOp.LESS_THAN, "100%"),
        ),
    )

    @Test
    fun matchAll() {
        assertTrue(unfinishedPdfs.matches(facts(), now))
        assertFalse(unfinishedPdfs.matches(facts(format = "EPUB"), now))
        assertFalse(unfinishedPdfs.matches(facts(addedDaysAgo = 45), now))
        assertFalse(unfinishedPdfs.matches(facts(progress = 100), now))
    }

    @Test
    fun matchAnyAndTags() {
        val q = SmartQuery(listOf(SmartRule(RuleField.TAG, RuleOp.IS, "mystery"), SmartRule(RuleField.NOTES, RuleOp.MORE_THAN, "5")), matchAll = false)
        assertTrue(q.matches(facts(tags = setOf("Mystery")), now))
        assertTrue(q.matches(facts(notes = 6), now))
        assertFalse(q.matches(facts(), now))
    }

    @Test
    fun neverOpenedCountsAsOld() {
        val q = SmartQuery(listOf(SmartRule(RuleField.OPENED_DAYS, RuleOp.OLDER_THAN_DAYS, "90")))
        assertTrue(q.matches(facts(), now))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val q = unfinishedPdfs.copy(rules = unfinishedPdfs.rules + SmartRule(RuleField.AUTHOR, RuleOp.CONTAINS, "a;b:c|d%"))
        assertEquals(q, SmartQuery.decode(q.encode()))
        assertEquals(null, SmartQuery.decode(""))
    }
}
