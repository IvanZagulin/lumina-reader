package com.lumina.reader.ui.reader

import com.lumina.reader.core.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookProgressTest {

    private val pageMap = BookPageMap(
        listOf(
            intArrayOf(0, 3, 7),
            intArrayOf(0),
            intArrayOf(0, 2, 5, 9)
        )
    )

    @Test
    fun pageMapNumbersPagesAcrossChapters() {
        assertEquals(8, pageMap.totalPages)
        assertEquals(0, pageMap.globalPageIndex(0, 0))
        assertEquals(3, pageMap.globalPageIndex(1, 0))
        assertEquals(4, pageMap.globalPageIndex(2, 0))
        assertEquals(7, pageMap.globalPageIndex(2, 3))
        // Out of range local pages stay inside their chapter.
        assertEquals(7, pageMap.globalPageIndex(2, 99))
    }

    @Test
    fun locateIsInverseOfGlobalPageIndex() {
        for (global in 0 until pageMap.totalPages) {
            val location = pageMap.locate(global)
            assertEquals(global, pageMap.globalPageIndex(location.chapterIndex, location.localPage))
        }
        assertEquals(BookPageLocation(0, 2, 7), pageMap.locate(2))
        assertEquals(BookPageLocation(1, 0, 0), pageMap.locate(3))
        assertEquals(BookPageLocation(2, 2, 5), pageMap.locate(6))
        assertEquals(BookPageLocation(2, 3, 9), pageMap.locate(100))
        assertEquals(BookPageLocation(0, 0, 0), pageMap.locate(-5))
    }

    @Test
    fun exactPositionUsesBookPages() {
        val position = bookPosition(
            chapterIndex = 2,
            localPage = 1,
            chapterPageCount = 4,
            chapterLengths = intArrayOf(300, 100, 400),
            pageMap = pageMap
        )
        assertTrue(position.isExact)
        assertEquals(6, position.pageNumber)
        assertEquals(8, position.totalPages)
        assertEquals(75f, position.percent, 0.001f)

        val lastPage = bookPosition(2, 3, 4, intArrayOf(300, 100, 400), pageMap)
        assertEquals(100f, lastPage.percent, 0.001f)
    }

    @Test
    fun estimateIsWeightedByTextLength() {
        // 1000 characters before, a 500 character chapter split into 5 pages.
        val position = bookPosition(
            chapterIndex = 1,
            localPage = 1,
            chapterPageCount = 5,
            chapterLengths = intArrayOf(1000, 500, 500),
            pageMap = null
        )
        assertFalse(position.isExact)
        assertEquals(20, position.totalPages)
        assertEquals(12, position.pageNumber)
        assertEquals(60f, position.percent, 0.001f)
    }

    @Test
    fun estimateIgnoresMapOfDifferentBook() {
        val position = bookPosition(0, 0, 1, intArrayOf(10, 10), pageMap)
        assertFalse(position.isExact)
    }

    @Test
    fun illustrationOnlyBookFallsBackToChapters() {
        val position = bookPosition(1, 0, 1, intArrayOf(0, 0, 0, 0), null)
        assertEquals(2, position.pageNumber)
        assertEquals(4, position.totalPages)
        assertEquals(50f, position.percent, 0.001f)
    }

    @Test
    fun paragraphProgressIsWeightedByText() {
        val chapters = listOf(
            chapter(0, "a".repeat(900)),
            chapter(1, "b".repeat(50), "c".repeat(50))
        )
        val lengths = chapterTextLengths(chapters)
        assertEquals(0f, paragraphProgressPercent(chapters, lengths, 0, 0), 0.001f)
        // A short final chapter is worth only its share of the text.
        assertEquals(90f, paragraphProgressPercent(chapters, lengths, 1, 0), 0.001f)
        assertEquals(95f, paragraphProgressPercent(chapters, lengths, 1, 1), 0.001f)
        assertEquals(100f, paragraphProgressPercent(chapters, lengths, 1, Int.MAX_VALUE), 0.001f)
    }

    @Test
    fun imageMarkersDoNotCountAsText() {
        val chapters = listOf(chapter(0, "[IMG:cover.jpg]", "  hello  "))
        assertEquals(5, chapterTextLengths(chapters)[0])
    }

    @Test
    fun percentFormattingIsStable() {
        assertEquals("42.3%", formatBookPercent(42.34f))
        assertEquals("100.0%", formatBookPercent(100f))
    }

    private fun chapter(index: Int, vararg paragraphs: String) = Chapter(
        index = index,
        title = "Глава ${index + 1}",
        content = paragraphs.joinToString("\n"),
        paragraphs = paragraphs.toList()
    )
}
