package com.lumina.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaginationTest {

    /** A paragraph of [lineCount] lines of [lineHeight] px, each [charsPerLine] characters long. */
    private fun lines(lineCount: Int, lineHeight: Float = 20f, charsPerLine: Int = 10): ParagraphLines =
        ParagraphLines(
            lineTops = FloatArray(lineCount) { it * lineHeight },
            lineBottoms = FloatArray(lineCount) { (it + 1) * lineHeight },
            lineStarts = IntArray(lineCount) { it * charsPerLine },
            textLength = lineCount * charsPerLine
        )

    private fun text(index: Int, lineCount: Int) = MeasuredParagraph.Text(index, lines(lineCount))

    private fun slices(pages: List<ReaderPage>) =
        pages.flatMap { it.blocks }.filterIsInstance<PageBlock.TextSlice>()

    @Test
    fun shortParagraphsShareAPage() {
        val pages = paginateParagraphs(
            paragraphs = listOf(text(0, 2), text(1, 2)),
            contentHeightPx = 200,
            titleHeightPx = 0,
            paragraphSpacingPx = 6
        )
        assertEquals(1, pages.size)
        val blocks = slices(pages)
        assertEquals(listOf(0, 1), blocks.map { it.paragraphIndex })
        assertTrue(blocks.all { it.endsParagraph && it.startLine == 0 && it.topPx == 0 })
        assertEquals(40, blocks[0].heightPx)
    }

    @Test
    fun splitParagraphLosesAndRepeatsNoText() {
        // 23 lines of 20 px on 100 px pages: the paragraph spans several pages.
        val paragraph = lines(23)
        val pages = paginateParagraphs(listOf(MeasuredParagraph.Text(0, paragraph)), 100, 0, 6)
        val parts = slices(pages)
        assertTrue(parts.size > 1)
        // Every slice starts where the previous one ended: the last word of a
        // page is never dropped (the old paginator lost it) nor repeated.
        assertEquals(0, parts.first().startOffset)
        parts.zipWithNext().forEach { (before, after) ->
            assertEquals(before.endOffset, after.startOffset)
            assertEquals(before.endLine, after.startLine)
        }
        assertEquals(paragraph.textLength, parts.last().endOffset)
        assertTrue(parts.last().endsParagraph)
        assertTrue(parts.dropLast(1).none { it.endsParagraph })
    }

    @Test
    fun continuationIsShiftedToItsFirstLine() {
        val pages = paginateParagraphs(listOf(text(0, 8)), 100, 0, 0)
        val second = slices(pages)[1]
        assertEquals(5, second.startLine)
        assertEquals(100, second.topPx)
        assertEquals(50, second.startOffset)
        assertEquals(TextAnchor(0, 50), pages[1].start)
    }

    @Test
    fun pagesNeverOverflow() {
        val paragraphs = (0 until 30).map { text(it, 1 + it % 7) }
        val pages = paginateParagraphs(paragraphs, contentHeightPx = 230, titleHeightPx = 45, paragraphSpacingPx = 6)
        pages.forEachIndexed { index, page ->
            val title = if (index == 0) 45 else 0
            val textHeight = page.blocks.filterIsInstance<PageBlock.TextSlice>().sumOf { it.heightPx }
            val gaps = page.blocks.filterIsInstance<PageBlock.TextSlice>().dropLast(1).count { it.endsParagraph } * 6
            assertTrue("page $index overflows", title + textHeight + gaps <= 230)
        }
    }

    @Test
    fun loneFirstLineMovesToTheNextPage() {
        // 4 lines fill 80 of 100 px; the next paragraph has room for one line only.
        val pages = paginateParagraphs(listOf(text(0, 4), text(1, 5)), 100, 0, 0)
        assertEquals(listOf(0), pages[0].blocks.map { it.paragraphIndex })
        val moved = pages[1].blocks.single() as PageBlock.TextSlice
        assertEquals(1, moved.paragraphIndex)
        assertEquals(0, moved.startLine)
    }

    @Test
    fun loneLastLineIsNotLeftForTheNextPage() {
        // 6 lines on a 5-line page would leave a single line behind.
        val pages = paginateParagraphs(listOf(text(0, 6)), 100, 0, 0)
        val parts = slices(pages)
        assertEquals(4, parts[0].endLine)
        assertEquals(2, parts[1].endLine - parts[1].startLine)
    }

    @Test
    fun titleIsReservedOnTheFirstPage() {
        val pages = paginateParagraphs(listOf(text(0, 5)), 100, titleHeightPx = 40, paragraphSpacingPx = 0)
        assertTrue(pages[0].showsTitle)
        assertFalse(pages[1].showsTitle)
        assertEquals(3, (pages[0].blocks.single() as PageBlock.TextSlice).endLine)
    }

    @Test
    fun titleAloneWhenNoLineFitsBelowIt() {
        val pages = paginateParagraphs(listOf(text(0, 3)), 100, titleHeightPx = 95, paragraphSpacingPx = 0)
        assertTrue(pages[0].showsTitle)
        assertTrue(pages[0].blocks.isEmpty())
        assertEquals(1, pages.size - 1)
    }

    @Test
    fun imageSharesALightlyFilledPage() {
        val pages = paginateParagraphs(
            listOf(text(0, 2), MeasuredParagraph.Image(1, "pic"), text(2, 1)),
            100,
            0,
            0
        )
        assertEquals(2, pages.size)
        assertTrue(pages[0].blocks.last() is PageBlock.Image)
        assertEquals(2, pages[1].blocks.single().paragraphIndex)
    }

    @Test
    fun imageAfterDenseTextGetsItsOwnPage() {
        val pages = paginateParagraphs(listOf(text(0, 4), MeasuredParagraph.Image(1, "pic")), 100, 0, 0)
        assertEquals(2, pages.size)
        assertTrue(pages[1].blocks.single() is PageBlock.Image)
        assertEquals(TextAnchor(1, 0), pages[1].start)
    }

    @Test
    fun emptyChapterHasOnePage() {
        val pages = paginateParagraphs(emptyList(), 100, titleHeightPx = 30, paragraphSpacingPx = 0)
        assertEquals(1, pages.size)
        assertTrue(pages[0].showsTitle)
    }

    @Test
    fun anchorRestoresThePageThatShowsIt() {
        val pages = paginateParagraphs(listOf(text(0, 3), text(1, 12), text(2, 3)), 100, 0, 0)
        val chapter = ChapterPages(0, pages)
        // Every character of every paragraph maps to the page that displays it.
        slices(pages).forEach { slice ->
            val pageIndex = pages.indexOfFirst { slice in it.blocks }
            for (offset in slice.startOffset until slice.endOffset) {
                assertEquals(pageIndex, chapter.pageIndexFor(TextAnchor(slice.paragraphIndex, offset)))
            }
        }
        assertEquals(0, chapter.pageIndexFor(TextAnchor.START))
        assertEquals(pages.lastIndex, chapter.pageIndexFor(TextAnchor.CHAPTER_END))
    }

    @Test
    fun pageStartRoundTripsAfterRepagination() {
        val paragraphs = listOf(text(0, 9), text(1, 9), text(2, 9))
        val small = ChapterPages(0, paginateParagraphs(paragraphs, 100, 0, 0))
        val large = ChapterPages(0, paginateParagraphs(paragraphs, 160, 0, 0))
        // The saved position is the first character of a page; after a layout
        // change the reader opens the page that contains it, not a later one.
        small.pages.forEach { page ->
            val restored = large.pages[large.pageIndexFor(page.start)]
            assertTrue(restored.start <= page.start)
            val next = large.pages.getOrNull(large.pageIndexFor(page.start) + 1)
            assertTrue(next == null || next.start > page.start)
        }
    }

    @Test
    fun visibleRangeEndsWhereTheNextPageStarts() {
        val pages = paginateParagraphs(listOf(text(0, 8)), 100, 0, 0)
        val chapter = ChapterPages(3, pages)
        val first = chapter.visibleRange(0)
        assertEquals(3, first.chapterIndex)
        assertEquals(TextAnchor(0, 0), first.start)
        assertEquals(pages[1].start, first.end)
        assertEquals(TextAnchor.CHAPTER_END, chapter.visibleRange(pages.lastIndex).end)
        assertTrue(first.contains(3, 0, 10))
        assertFalse(first.contains(3, 0, pages[1].start.charOffset))
    }

    @Test
    fun fittingLinesUsesWholePixels() {
        val paragraph = ParagraphLines(
            lineTops = floatArrayOf(0f, 20.4f, 40.8f),
            lineBottoms = floatArrayOf(20.4f, 40.8f, 61.2f),
            lineStarts = intArrayOf(0, 5, 10),
            textLength = 15
        )
        assertEquals(2, fittingLines(paragraph, 0, 41))
        assertEquals(1, fittingLines(paragraph, 0, 40))
        assertEquals(0, fittingLines(paragraph, 0, 20))
        assertEquals(21, paragraph.heightPx(1, 2))
    }

    @Test
    fun gapSeparatesBlocksButNeverOpensAPage() {
        val pages = paginateParagraphs(
            listOf(
                MeasuredParagraph.Gap(0, 10),
                text(1, 2),
                MeasuredParagraph.Gap(2, 10),
                text(3, 2),
                MeasuredParagraph.Gap(4, 30),
                text(5, 2)
            ),
            contentHeightPx = 100,
            titleHeightPx = 0,
            paragraphSpacingPx = 0
        )
        // 40 + 10 + 40 = 90 px; the 30 px gap does not fit and is dropped.
        assertEquals(listOf(1, 2, 3), pages[0].blocks.map { it.paragraphIndex })
        assertTrue(pages[0].blocks[1] is PageBlock.Gap)
        assertEquals(listOf(5), pages[1].blocks.map { it.paragraphIndex })
        assertTrue(pages.none { it.blocks.firstOrNull() is PageBlock.Gap })
        assertEquals(TextAnchor(5, 0), pages[1].start)
    }
}
