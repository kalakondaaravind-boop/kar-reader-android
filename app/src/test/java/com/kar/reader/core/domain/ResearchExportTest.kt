package com.kar.reader.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchExportTest {

    private val book = ExportBook(
        id = "b1",
        title = "Moby-Dick",
        authors = listOf("Herman Melville"),
        year = "1851",
        publisher = null,
        format = "EPUB",
        tags = listOf("Sea Stories", "Classics"),
        highlights = listOf(
            ExportHighlight("Call me Ishmael.", "Chapter 1", "Key idea", "Famous opening", 0L),
            ExportHighlight("A \"quoted\", comma line", "Chapter 2", "Question", null, 86_400_000L),
        ),
        notes = listOf(ExportNote("Themes", "Obsession and the sea.", null, 0L)),
    )

    @Test
    fun markdownGroupsByMeaningAndCites() {
        val md = ResearchExport.markdown(book)
        assertTrue(md.startsWith("# Moby-Dick"))
        assertTrue(md.contains("### Key idea"))
        assertTrue(md.contains("> Call me Ishmael."))
        assertTrue(md.contains("(Melville, 1851, Chapter 1)"))
        assertTrue(md.contains("## Notes"))
        assertTrue(md.contains("*Moby-Dick*"))
    }

    @Test
    fun obsidianHasFrontMatterAndTags() {
        val text = ResearchExport.obsidian(book)
        assertTrue(text.startsWith("---\ntitle: \"Moby-Dick\""))
        assertTrue(text.contains("  - sea-stories"))
        assertTrue(text.contains("  - kar-reader"))
        assertEquals("Moby-Dick.md", ResearchExport.obsidianFileName(book))
        assertEquals("A B", ResearchExport.safeFileName("A/B"))
    }

    @Test
    fun bibtexEntries() {
        val bib = ResearchExport.bibtex(listOf(book, book))
        assertTrue(bib.contains("@book{melville1851mobydick,"))
        assertTrue(bib.contains("@book{melville1851mobydick2,"))
        assertTrue(bib.contains("author = {Herman Melville}"))
    }

    @Test
    fun csvEscapesAndBlocksFormulas() {
        val csv = ResearchExport.csv(listOf(book))
        assertTrue(csv.startsWith("book,authors,year,type,meaning,location,text,note,date\r\n"))
        assertTrue(csv.contains("\"A \"\"quoted\"\", comma line\""))
        assertEquals("'=SUM(A1)", ResearchExport.csvCell("=SUM(A1)"))
        assertFalse(ResearchExport.csvCell("plain").contains("\""))
        assertTrue(csv.contains("1970-01-02"))
    }
}
