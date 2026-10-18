package com.kar.reader.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationsTest {

    private val moby = CitationSource(listOf("Herman Melville"), "Moby-Dick; or, The Whale", "1851", "Harper & Brothers", "New York")

    @Test
    fun parsesNames() {
        assertEquals(PersonName("Herman", "Melville"), PersonName.parse("Herman Melville"))
        assertEquals(PersonName("Herman", "Melville"), PersonName.parse("Melville, Herman"))
        assertEquals("M. W.", PersonName.parse("Mary Wollstonecraft Shelley").initials)
        assertEquals(PersonName("", "Homer"), PersonName.parse("Homer"))
    }

    @Test
    fun apaBibliography() {
        assertEquals("Melville, H. (1851). Moby-Dick; or, The Whale. Harper & Brothers.", Citations.bibliography(moby, CitationStyle.APA))
        val noYear = moby.copy(year = null, publisher = null)
        assertEquals("Melville, H. (n.d.). Moby-Dick; or, The Whale.", Citations.bibliography(noYear, CitationStyle.APA))
    }

    @Test
    fun mlaAndChicago() {
        assertEquals("Melville, Herman. Moby-Dick; or, The Whale. Harper & Brothers, 1851.", Citations.bibliography(moby, CitationStyle.MLA))
        assertEquals("Melville, Herman. Moby-Dick; or, The Whale. New York: Harper & Brothers, 1851.", Citations.bibliography(moby, CitationStyle.CHICAGO))
    }

    @Test
    fun multipleAuthors() {
        val two = CitationSource(listOf("Ada Lovelace", "Charles Babbage"), "Notes", "1843")
        assertEquals("Lovelace, A., & Babbage, C. (1843). Notes.", Citations.bibliography(two, CitationStyle.APA))
        assertEquals("Lovelace, Ada, and Charles Babbage. Notes. 1843.", Citations.bibliography(two, CitationStyle.MLA))
        val three = two.copy(authors = listOf("A One", "B Two", "C Three"))
        assertTrue(Citations.bibliography(three, CitationStyle.MLA).startsWith("One, A, et al."))
        assertEquals("(One et al., 1843, p. 4)", Citations.inText(three, CitationStyle.APA, "4"))
    }

    @Test
    fun inTextCitations() {
        assertEquals("(Melville, 1851, p. 14)", Citations.inText(moby, CitationStyle.APA, "14"))
        assertEquals("(Melville, 1851, Chapter 1)", Citations.inText(moby, CitationStyle.APA, "Chapter 1"))
        assertEquals("(Melville 14)", Citations.inText(moby, CitationStyle.MLA, "14"))
        assertEquals("(Melville)", Citations.inText(moby, CitationStyle.MLA, null))
    }

    @Test
    fun italicHookWrapsTitleOnly() {
        assertEquals("Melville, H. (1851). *Moby-Dick; or, The Whale*. Harper & Brothers.", Citations.bibliography(moby, CitationStyle.APA) { "*$it*" })
    }
}
