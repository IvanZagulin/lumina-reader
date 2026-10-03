package com.lumina.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.PageTurnAnimation
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReaderTapZones
import com.lumina.reader.core.model.ReaderThemeMode
import com.lumina.reader.core.model.ReadingTheme
import com.lumina.reader.core.model.resetToDefaults
import com.lumina.reader.core.model.toggledDayNight
import com.lumina.reader.core.model.withThemeChoice
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.reader.chrome.chapterAtFraction
import com.lumina.reader.ui.reader.chrome.chapterStartFractions
import com.lumina.reader.ui.reader.chrome.formatPageLabel
import com.lumina.reader.ui.reader.chrome.formatPercentLabel
import com.lumina.reader.ui.reader.chrome.percentWord
import com.lumina.reader.ui.reader.chrome.scrubberStateDescription
import com.lumina.reader.ui.reader.chrome.timeLeftLabel
import com.lumina.reader.ui.reader.footnote.footnotePlacement
import com.lumina.reader.ui.reader.pageturn.curlPageAlpha
import com.lumina.reader.ui.reader.pageturn.effectivePageTurn
import com.lumina.reader.ui.reader.pageturn.tapTurnSpec
import com.lumina.reader.ui.reader.selection.selectionMenuPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChromeLogicTest {

    @Test
    fun chapterTicksFollowTheTextLength() {
        val starts = chapterStartFractions(intArrayOf(100, 300, 0, 600))
        assertEquals(4, starts.size)
        assertEquals(0f, starts[0], 1e-6f)
        assertEquals(0.1f, starts[1], 1e-6f)
        assertEquals(0.4f, starts[2], 1e-6f)
        assertEquals(0.4f, starts[3], 1e-6f)
        // An empty chapter shares its start with the next one; the later one wins.
        assertEquals(3, chapterAtFraction(starts, 0.4f))
        assertEquals(0, chapterAtFraction(starts, 0.05f))
        assertEquals(1, chapterAtFraction(starts, 0.39f))
        assertEquals(3, chapterAtFraction(starts, 1f))
        // Without text every chapter gets the same share.
        val even = chapterStartFractions(intArrayOf(0, 0))
        assertEquals(0.5f, even[1], 1e-6f)
        assertEquals(0, chapterStartFractions(IntArray(0)).size)
    }

    @Test
    fun scrubberSpeaksPercentAndChapter() {
        assertEquals("42 процента, глава 7", scrubberStateDescription(0.42f, 7))
        assertEquals("1 процент, глава 1", scrubberStateDescription(0.01f, 1))
        assertEquals("11 процентов, глава 2", scrubberStateDescription(0.11f, 2))
        assertEquals("процентов", percentWord(25))
        assertEquals("процента", percentWord(23))
    }

    @Test
    fun footerLabels() {
        assertEquals("42,3 %", formatPercentLabel(42.34f))
        assertEquals("100,0 %", formatPercentLabel(120f))
        assertEquals("214 / 530", formatPageLabel(BookPosition(214, 530, 40f, isExact = true)))
        assertEquals("≈ 3 / 90", formatPageLabel(BookPosition(3, 90, 3f, isExact = false)))
        assertEquals("≈ 12 мин до конца главы", timeLeftLabel(12))
        assertNull(timeLeftLabel(null))
    }

    @Test
    fun chromeColorsComeFromTheReadingTheme() {
        val light = ReaderChromeColors.of(ReadingTheme.CREAM)
        assertEquals(ReadingTheme.CREAM.bgComposeColor, light.page)
        assertEquals(0.94f, light.bg.alpha, 0.01f)
        assertEquals(ReadingTheme.CREAM.accentComposeColor, light.accent)
        assertEquals(Color.White, light.onAccent)
        assertEquals(Color(0xFF1F1A16), light.inverseBg)
        assertFalse(light.isDark)
        val dark = ReaderChromeColors.of(ReadingTheme.OLED_BLACK)
        assertEquals(Color(0xFFF2EEE6), dark.inverseBg)
        assertEquals(ReadingTheme.OLED_BLACK.bgComposeColor, dark.onAccent)
        assertEquals(0.12f, dark.border.alpha, 0.01f)
        // The selected tint is opaque so it reads the same over any page.
        assertEquals(1f, dark.selectedBg.alpha, 0.001f)
    }

    @Test
    fun classicTapZones() {
        fun at(x: Float) = tapZoneAction(x, 500f, 1000f, 1000f, ReaderTapZones.CLASSIC, inverted = false)
        assertEquals(TapAction.PREVIOUS, at(100f))
        assertEquals(TapAction.MENU, at(500f))
        assertEquals(TapAction.NEXT, at(900f))
        assertEquals(
            TapAction.NEXT,
            tapZoneAction(100f, 500f, 1000f, 1000f, ReaderTapZones.CLASSIC, inverted = true)
        )
    }

    @Test
    fun oneHandedTapZones() {
        fun at(x: Float, y: Float) = tapZoneAction(x, y, 1000f, 1000f, ReaderTapZones.ONE_HAND, inverted = false)
        assertEquals(TapAction.PREVIOUS, at(100f, 500f))
        assertEquals(TapAction.MENU, at(500f, 500f))
        // Outside the centre box everything else turns forward.
        assertEquals(TapAction.NEXT, at(500f, 100f))
        assertEquals(TapAction.NEXT, at(300f, 500f))
        assertEquals(TapAction.NEXT, at(950f, 950f))
    }

    @Test
    fun readAloudFollowsOnlyWhileTheReaderFollows() {
        val page = VisibleRange(2, TextAnchor(10, 0), TextAnchor(14, 0))
        assertTrue(shouldFollowReadAloud(null, page))
        assertTrue(shouldFollowReadAloud(2 to TextAnchor(12, 40), page))
        assertTrue(shouldFollowReadAloud(2 to TextAnchor(12, 40), null))
        // The reader paged away from the voice: leave the page alone.
        assertFalse(shouldFollowReadAloud(2 to TextAnchor(30, 0), page))
        assertFalse(shouldFollowReadAloud(1 to TextAnchor(12, 0), page))
    }

    @Test
    fun spokenSentenceMarksOnlyItsParagraph() {
        val mark = TtsSentenceMark(chapterIndex = 3, paragraphIndex = 7, start = 10, end = 42)
        assertEquals(OffsetRange(10, 42), mark.rangeFor(3, 7))
        assertNull(mark.rangeFor(3, 8))
        assertNull(mark.rangeFor(4, 7))
        assertNull(TtsSentenceMark(3, 7, 5, 5).rangeFor(3, 7))
    }

    @Test
    fun selectionMenuGoesAboveOrBelowAndStaysOnScreen() {
        val container = IntSize(1080, 2000)
        val menu = IntSize(800, 260)
        val above = selectionMenuPosition(Rect(400f, 900f, 700f, 960f), menu, container, margin = 20, gap = 30, minTop = 150)
        assertEquals(900 - 30 - 260, above.y)
        assertEquals(150, above.x)
        // Not enough room above: below the selection.
        val below = selectionMenuPosition(Rect(400f, 200f, 700f, 260f), menu, container, margin = 20, gap = 30, minTop = 150)
        assertEquals(290, below.y)
        // Clamped to the edges.
        val edge = selectionMenuPosition(Rect(0f, 1900f, 10f, 1990f), menu, container, margin = 20, gap = 30, minTop = 150)
        assertEquals(20, edge.x)
        assertTrue(edge.y + menu.height <= container.height - 20)
    }

    @Test
    fun footnoteCardPrefersTheSpaceBelow() {
        val container = IntSize(1000, 2000)
        val card = IntSize(600, 500)
        val low = footnotePlacement(Offset(500f, 400f), card, container, margin = 16, gap = 24)
        assertTrue(low.below)
        assertEquals(IntOffset(200, 424), low.offset)
        assertEquals(300, low.caretX)
        val high = footnotePlacement(Offset(900f, 1800f), card, container, margin = 16, gap = 24)
        assertFalse(high.below)
        assertEquals(1800 - 24 - 500, high.offset.y)
        assertEquals(384, high.offset.x)
        assertEquals("12", footnoteNumber("note_12"))
        assertEquals("", footnoteNumber("n"))
    }

    @Test
    fun pageTurnStyleFallsBackWhenNeeded() {
        assertEquals(PageTurnAnimation.CURL, effectivePageTurn(PageTurnAnimation.CURL, curlSupported = true, readAloudDriving = false))
        assertEquals(PageTurnAnimation.SLIDE, effectivePageTurn(PageTurnAnimation.CURL, curlSupported = false, readAloudDriving = false))
        assertEquals(PageTurnAnimation.SLIDE, effectivePageTurn(PageTurnAnimation.CURL, curlSupported = true, readAloudDriving = true))
        assertEquals(PageTurnAnimation.FLIP, effectivePageTurn(PageTurnAnimation.FLIP, curlSupported = false, readAloudDriving = true))
        assertNotNull(tapTurnSpec(PageTurnAnimation.SLIDE, reducedMotion = false))
        assertNotNull(tapTurnSpec(PageTurnAnimation.FLIP, reducedMotion = false))
        assertNull(tapTurnSpec(PageTurnAnimation.NONE, reducedMotion = false))
        assertNull(tapTurnSpec(PageTurnAnimation.SLIDE, reducedMotion = true))
    }

    @Test
    fun curlShowsOnlyTheCurledPageAndTheOneBelow() {
        // Idle: just the current page.
        assertEquals(1f, curlPageAlpha(page = 5, offset = 0f, activePage = -1), 0f)
        assertEquals(0f, curlPageAlpha(page = 6, offset = -1f, activePage = -1), 0f)
        assertEquals(0f, curlPageAlpha(page = 4, offset = 1f, activePage = -1), 0f)
        // Forward curl of page 5 reveals page 6.
        assertEquals(1f, curlPageAlpha(page = 5, offset = 0f, activePage = 5), 0f)
        assertEquals(1f, curlPageAlpha(page = 6, offset = -1f, activePage = 5), 0f)
        assertEquals(0f, curlPageAlpha(page = 7, offset = -2f, activePage = 5), 0f)
        // Backward: page 4 comes back over page 5.
        assertEquals(1f, curlPageAlpha(page = 4, offset = 1f, activePage = 4), 0f)
        assertEquals(1f, curlPageAlpha(page = 5, offset = 0f, activePage = 4), 0f)
    }

    @Test
    fun themeChoicesKeepTheDayNightPair() {
        val base = ReaderSettings(theme = ReadingTheme.CREAM, dayTheme = ReadingTheme.CREAM, nightTheme = ReadingTheme.OLED_BLACK)
        val sepia = base.withThemeChoice(ReadingTheme.SEPIA)
        assertEquals(ReaderThemeMode.MANUAL, sepia.themeMode)
        assertEquals(ReadingTheme.SEPIA, sepia.theme)
        assertEquals(ReadingTheme.SEPIA, sepia.dayTheme)
        assertEquals(ReadingTheme.OLED_BLACK, sepia.nightTheme)
        val slate = sepia.withThemeChoice(ReadingTheme.DARK_SLATE)
        assertEquals(ReadingTheme.DARK_SLATE, slate.nightTheme)
        assertEquals(ReadingTheme.SEPIA, slate.dayTheme)
        assertEquals(ReaderThemeMode.SYSTEM, slate.withThemeChoice(null).themeMode)

        // «Тема» toggles to the other half of the pair.
        assertEquals(ReadingTheme.SEPIA, slate.toggledDayNight(systemInDarkMode = false).theme)
        assertEquals(ReadingTheme.DARK_SLATE, sepia.copy(nightTheme = ReadingTheme.DARK_SLATE).toggledDayNight(false).theme)
        val auto = slate.withThemeChoice(null)
        val toggled = auto.toggledDayNight(systemInDarkMode = true)
        assertEquals(ReaderThemeMode.MANUAL, toggled.themeMode)
        assertEquals(ReadingTheme.SEPIA, toggled.theme)
    }

    @Test
    fun resetKeepsThemesAndVoice() {
        val custom = ReaderSettings(
            fontSizeSp = 26,
            theme = ReadingTheme.SEPIA,
            ttsSpeed = 1.5f,
            pageTurnAnimation = PageTurnAnimation.CURL,
            isContinuousScroll = true
        )
        val reset = custom.resetToDefaults()
        assertEquals(18, reset.fontSizeSp)
        assertEquals(ReadingTheme.SEPIA, reset.theme)
        assertEquals(1.5f, reset.ttsSpeed)
        assertEquals(PageTurnAnimation.SLIDE, reset.pageTurnAnimation)
        assertFalse(reset.isContinuousScroll)
    }

    @Test
    fun scrubberFractionsMapBackToPositions() {
        val chapters = listOf(
            Chapter(index = 0, title = "Один", paragraphs = listOf("aaaaaaaaaa", "bbbbbbbbbb")),
            Chapter(index = 1, title = "Два", paragraphs = listOf("cccccccccc", "", "dddddddddd"))
        )
        val lengths = chapterTextLengths(chapters)
        assertEquals(ReaderPosition(0, 0, 0), locateBookFraction(chapters, lengths, 0f))
        assertEquals(ReaderPosition(0, 1, 5), locateBookFraction(chapters, lengths, 0.375f))
        assertEquals(ReaderPosition(1, 0, 0), locateBookFraction(chapters, lengths, 0.5f))
        // The blank paragraph is skipped.
        assertEquals(ReaderPosition(1, 2, 0), locateBookFraction(chapters, lengths, 0.75f))
        assertEquals(1, locateBookFraction(chapters, lengths, 1f).chapterIndex)
        assertEquals(ReaderPosition(0, 0, 0), locateBookFraction(emptyList(), IntArray(0), 0.5f))
    }

    @Test
    fun decimalsAreWrittenTheRussianWay() {
        assertEquals("1,45", formatDecimal(1.45f))
        assertEquals("2", formatDecimal(2f))
        assertEquals("1,5", formatDecimal(1.5f))
    }
}
