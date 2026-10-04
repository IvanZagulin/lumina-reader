package com.lumina.reader.ui.reader

import com.lumina.reader.core.model.TocItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderChapterLabelTest {

    @Test
    fun numbersAreReadTheWayBooksWriteThem() {
        assertEquals("15", bookChapterNumber("15"))
        assertEquals("15", bookChapterNumber("Глава 15. Возвращение"))
        assertEquals("3", bookChapterNumber("Chapter 3"))
        assertEquals("7", bookChapterNumber("гл. 7"))
        assertEquals("XV", bookChapterNumber("XV"))
        assertEquals("XV", bookChapterNumber("XV. Возвращение"))
        assertEquals("IV", bookChapterNumber("Часть IV"))
    }

    @Test
    fun titlesWithoutANumberHaveNone() {
        assertNull(bookChapterNumber("Пролог"))
        assertNull(bookChapterNumber("Об авторе"))
        assertNull(bookChapterNumber("MIX"))
        assertNull(bookChapterNumber("1984 год"))
        assertNull(bookChapterNumber(""))
    }

    // A book whose first two files are a cover and a title page: the reader's chapter
    // index is 2 more than the book's own number.
    private val toc = listOf(
        TocItem("a", "Пролог", chapterIndex = 2),
        TocItem("b", "1", chapterIndex = 3),
        TocItem("c", "Глава 2. Дорога", chapterIndex = 4),
        TocItem("d", "Эпилог после всего этого", chapterIndex = 5)
    )

    @Test
    fun theFooterFollowsTheContentsNotTheFileCount() {
        assertEquals("Гл. 1", chapterNumberLabel(toc, tocIndex = 1, chapterIndex = 3))
        assertEquals("Гл. 2", chapterNumberLabel(toc, tocIndex = 2, chapterIndex = 4))
        assertEquals("Пролог", chapterNumberLabel(toc, tocIndex = 0, chapterIndex = 2))
        assertEquals("Эпилог после…", chapterNumberLabel(toc, tocIndex = 3, chapterIndex = 5))
        assertEquals("Начало", chapterNumberLabel(toc, tocIndex = -1, chapterIndex = 0))
        assertEquals("Гл. 5", chapterNumberLabel(emptyList(), tocIndex = -1, chapterIndex = 4))
    }

    @Test
    fun theScrubberNamesChaptersAsTheBookDoes() {
        assertEquals("Глава 1", scrubberChapterTitle(toc, chapterIndex = 3, fallbackTitle = "x"))
        assertEquals("Глава 2. Дорога", scrubberChapterTitle(toc, chapterIndex = 4, fallbackTitle = "x"))
        assertEquals("Глава 5 · x", scrubberChapterTitle(emptyList(), chapterIndex = 4, fallbackTitle = "x"))
        assertEquals(2, chapterNumberForSpeech(toc, tocIndex = 2, chapterIndex = 4))
        assertEquals(1, chapterNumberForSpeech(toc, tocIndex = 0, chapterIndex = 2))
    }
}
