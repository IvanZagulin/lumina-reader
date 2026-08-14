package com.lumina.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterPagerLayoutTest {

    @Test
    fun middleChapterHasBothBoundariesAndStableContentIndices() {
        val layout = ChapterPagerLayout(
            contentPageCount = 3,
            hasPreviousChapter = true,
            hasNextChapter = true
        )

        assertEquals(5, layout.pageCount)
        assertEquals(PageTurnDirection.PREVIOUS, layout.boundaryDirectionFor(0))
        assertEquals(1, layout.pagerPageForContent(0))
        assertEquals(0, layout.contentPageForPager(1))
        assertEquals(2, layout.contentPageForPager(3))
        assertEquals(PageTurnDirection.NEXT, layout.boundaryDirectionFor(4))
        assertNull(layout.contentPageForPager(4))
    }

    @Test
    fun firstAndLastChaptersOnlyExposeExistingNeighbour() {
        val first = ChapterPagerLayout(2, hasPreviousChapter = false, hasNextChapter = true)
        assertEquals(3, first.pageCount)
        assertEquals(0, first.pagerPageForContent(0))
        assertNull(first.boundaryDirectionFor(0))
        assertEquals(PageTurnDirection.NEXT, first.boundaryDirectionFor(2))

        val last = ChapterPagerLayout(2, hasPreviousChapter = true, hasNextChapter = false)
        assertEquals(3, last.pageCount)
        assertEquals(PageTurnDirection.PREVIOUS, last.boundaryDirectionFor(0))
        assertEquals(2, last.pagerPageForContent(1))
        assertNull(last.boundaryDirectionFor(2))
    }

    @Test
    fun singleChapterHasNoSyntheticPages() {
        val layout = ChapterPagerLayout(1, false, false)

        assertEquals(1, layout.pageCount)
        assertEquals(0, layout.pagerPageForContent(0))
        assertEquals(0, layout.contentPageForPager(0))
        assertNull(layout.boundaryDirectionFor(0))
    }
}
