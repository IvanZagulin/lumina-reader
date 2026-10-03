package com.lumina.reader.ui.reader

import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReaderThemeMode
import com.lumina.reader.core.model.ReadingTheme
import com.lumina.reader.core.model.effectiveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderViewerSupportTest {

    @Test
    fun navigationPagesDoNotCountAsReading() {
        val gate = PageReportGate()
        gate.expect(page = 4, countsAsReading = false)
        assertFalse(gate.consume(4))
        // Pages turned afterwards by the reader count.
        assertTrue(gate.consume(5))
        assertTrue(gate.consume(4))

        // A page turn into the next chapter counts.
        gate.expect(page = 1, countsAsReading = true)
        assertTrue(gate.consume(1))

        // A swipe that lands elsewhere before the jump settles counts and
        // cancels the expectation.
        gate.expect(page = 9, countsAsReading = false)
        assertTrue(gate.consume(8))
        assertTrue(gate.consume(9))

        gate.expect(page = 2, countsAsReading = false)
        gate.clear()
        assertTrue(gate.consume(2))
    }

    @Test
    fun scrollItemsSurroundTheParagraphs() {
        val items = ScrollItems(hasPrevious = true, paragraphCount = 3, hasNext = true)
        assertEquals(1, items.titleItemIndex)
        assertNull(items.paragraphForItem(0))
        assertNull(items.paragraphForItem(1))
        assertEquals(0, items.paragraphForItem(2))
        assertEquals(2, items.paragraphForItem(4))
        assertNull(items.paragraphForItem(5))
        assertEquals(5, items.endItemIndex)
        assertEquals(6, items.itemCount)

        assertEquals(1, items.itemIndexFor(TextAnchor.START))
        assertEquals(3, items.itemIndexFor(TextAnchor(1, 0)))
        assertEquals(2, items.itemIndexFor(TextAnchor(0, 15)))
        assertEquals(4, items.itemIndexFor(TextAnchor(99, 0)))
        assertEquals(5, items.itemIndexFor(TextAnchor.CHAPTER_END))

        val first = ScrollItems(hasPrevious = false, paragraphCount = 0, hasNext = false)
        assertEquals(0, first.titleItemIndex)
        assertEquals(0, first.itemIndexFor(TextAnchor(3, 3)))
        assertEquals(1, first.endItemIndex)
    }

    @Test
    fun decorationsBelongToTheirChapterAndParagraph() {
        val highlights = listOf(
            highlight(chapter = 2, paragraph = 5, start = 3, end = 9),
            highlight(chapter = 2, paragraph = 5, start = 20, end = 25),
            highlight(chapter = 3, paragraph = 5, start = 0, end = 4),
            // Created before positions existed: nothing to draw.
            highlight(chapter = 2, paragraph = 0, start = 0, end = 0)
        )
        val decorations = ChapterDecorations.build(2, highlights, SearchMatch(2, 7, 1, 4))
        assertEquals(listOf(3, 20), decorations.highlightsFor(5).map { it.start })
        assertTrue(decorations.highlightsFor(0).isEmpty())
        assertEquals(OffsetRange(1, 4), decorations.searchMatchFor(7))
        assertNull(decorations.searchMatchFor(5))
        assertNull(ChapterDecorations.build(3, highlights, SearchMatch(2, 7, 1, 4)).searchMatchFor(7))
    }

    @Test
    fun timeLeftIsReadable() {
        assertNull(formatTimeLeft(null))
        assertNull(formatTimeLeft(0))
        assertEquals("7 мин", formatTimeLeft(7))
        assertEquals("1 ч", formatTimeLeft(60))
        assertEquals("2 ч 05 мин", formatTimeLeft(125))
    }

    @Test
    fun themeFollowsTheSystemOnlyWhenAsked() {
        val manual = ReaderSettings(theme = ReadingTheme.SEPIA)
        assertEquals(ReadingTheme.SEPIA, manual.effectiveTheme(systemInDarkMode = true))

        val system = manual.copy(themeMode = ReaderThemeMode.SYSTEM)
        assertEquals(ReadingTheme.SEPIA, system.effectiveTheme(systemInDarkMode = false))
        assertEquals(ReadingTheme.WARM_AMBER, system.effectiveTheme(systemInDarkMode = true))
        ReadingTheme.entries.forEach { theme ->
            assertTrue(theme.isDark != theme.counterpart.isDark)
        }
    }

    @Test
    fun newSettingsHaveTheDocumentedDefaults() {
        val defaults = ReaderSettings()
        assertTrue(defaults.hyphenation)
        assertEquals(1.5f, defaults.firstLineIndentEm)
        assertEquals(6, defaults.paragraphSpacingDp)
        assertTrue(defaults.showTimeLeft)
        assertFalse(defaults.tapZonesInverted)
        assertEquals(ReaderThemeMode.MANUAL, defaults.themeMode)
    }

    private fun highlight(chapter: Int, paragraph: Int, start: Int, end: Int) = ReadingHighlight(
        bookId = 1,
        chapterIndex = chapter,
        selectedText = "x",
        paragraphIndex = paragraph,
        startOffset = start,
        endOffset = end
    )
}
