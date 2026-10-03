package com.lumina.reader.ui.reader

import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParagraphMarkup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSearchTest {

    private fun chapter(index: Int, vararg paragraphs: String) =
        Chapter(index = index, title = "Глава ${index + 1}", paragraphs = paragraphs.toList())

    @Test
    fun normalizationFoldsCaseAndYoKeepingLength() {
        val text = "Ёжик ЁЛКА ещё Ok"
        val normalized = normalizeForSearch(text)
        assertEquals("ежик елка еще ok", normalized)
        assertEquals(text.length, normalized.length)
    }

    @Test
    fun searchIsCaseAndYoInsensitive() {
        val chapters = listOf(
            chapter(0, "Ещё одна ёлка.", "[IMG:tree.png]", "Ничего"),
            chapter(1, "ЕЛКА и елка")
        )
        val outcome = searchChapters(chapters, "ёлка")
        assertFalse(outcome.truncated)
        assertEquals(3, outcome.results.size)
        val first = outcome.results[0]
        assertEquals(0, first.chapterIndex)
        assertEquals(0, first.paragraphIndex)
        assertEquals("Ещё одна ёлка.".indexOf("ёлка"), first.matchStart)
        assertEquals(first.matchStart + 4, first.matchEnd)
        assertEquals(listOf(0, 7), outcome.results.drop(1).map { it.matchStart })
        assertEquals("Глава 2", outcome.results[1].chapterTitle)
    }

    @Test
    fun offsetsAreInPlainText() {
        val raw = ParagraphMarkup.block(
            ParagraphMarkup.BlockStyle.EPIGRAPH,
            "Он " + ParagraphMarkup.strong("сказал") + ParagraphMarkup.noteRef("1", "n1") + ": тише"
        )
        val result = searchChapters(listOf(chapter(0, raw)), "тише").results.single()
        val plain = ParagraphMarkup.plainText(raw)
        assertEquals("тише", plain.substring(result.matchStart, result.matchEnd))
        assertEquals(
            "тише",
            result.snippet.substring(result.snippetMatchStart, result.snippetMatchEnd)
        )
    }

    @Test
    fun pdfChaptersAndShortQueriesFindNothing() {
        val pdf = listOf(Chapter(index = 0, title = "Страница 1", paragraphs = emptyList(), pdfPageNumber = 0))
        assertTrue(searchChapters(pdf, "текст").results.isEmpty())
        assertTrue(searchChapters(listOf(chapter(0, "а б в")), "а").results.isEmpty())
        assertTrue(searchChapters(listOf(chapter(0, "текст")), "  ").results.isEmpty())
    }

    @Test
    fun searchStopsAtTheLimit() {
        val chapters = listOf(chapter(0, "да ".repeat(20)))
        val outcome = searchChapters(chapters, "да", maxResults = 5)
        assertEquals(5, outcome.results.size)
        assertTrue(outcome.truncated)
    }

    @Test
    fun cancellationIsChecked() {
        var calls = 0
        searchChapters(listOf(chapter(0, "раз", "два", "три")), "ра") { calls++ }
        assertEquals(3, calls)
    }

    @Test
    fun snippetKeepsContextAroundTheMatch() {
        val text = "Начало. " + "слово ".repeat(20) + "цель" + " хвост".repeat(20)
        val start = text.indexOf("цель")
        val snippet = buildSnippet(text, start, start + 4)
        assertTrue(snippet.text.startsWith("…"))
        assertTrue(snippet.text.endsWith("…"))
        assertEquals("цель", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
        assertTrue(snippet.text.length <= 4 + 2 * SEARCH_SNIPPET_CONTEXT + 2)

        val short = buildSnippet("коротко", 0, 7)
        assertEquals(Snippet("коротко", 0, 7), short)
    }

    @Test
    fun selectionPrefersTheVisiblePart() {
        val paragraphs = listOf(
            VisibleParagraphText(3, "кот и кот", visibleStart = 5, visibleEnd = 9)
        )
        assertEquals(ParagraphTextRange(3, 6, 9), locateSelection("кот", paragraphs))
        assertEquals(ParagraphTextRange(3, 0, 5), locateSelection(" кот и ", paragraphs))
    }

    @Test
    fun selectionAcrossParagraphsMapsToTheFirstOne() {
        val paragraphs = listOf(
            VisibleParagraphText(0, "Первый абзац кончается так"),
            VisibleParagraphText(1, "Второй начинается иначе")
        )
        // Compose copies a multi-paragraph selection without separators.
        val range = locateSelection("кончается такВторой начинается", paragraphs)
        assertEquals(ParagraphTextRange(0, 13, 26), range)
        assertNull(locateSelection("нет такого текста", paragraphs))
        assertNull(locateSelection("   ", paragraphs))
    }

    @Test
    fun genericChapterTitlesAreRenamed() {
        assertEquals("Глава 3", displayChapterTitle("Раздел 7", 2))
        assertEquals("Глава 1", displayChapterTitle(" ", 0))
        assertEquals("Пролог", displayChapterTitle("Пролог", 0))
    }
}
