package com.lumina.reader.ui.reader

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How wide a page of text is allowed to get.
 *
 * A phone must keep every pixel it has - the limit is only there for landscape
 * and for tablets, where the page used the whole window and a line ran past a
 * hundred characters.
 */
class TextColumnWidthTest {

    @Test
    fun aPhoneIsNeverNarrowedByTheLimit() {
        // The widest phones in portrait are around 430 dp; the limit must stay
        // above that even at the smallest reading size.
        assertTrue(textColumnWidth(fontSizeSp = 12) >= 480.dp)
        assertTrue(textColumnWidth(fontSizeSp = 14) >= 480.dp)
    }

    @Test
    fun theLimitGrowsWithTheReadingSize() {
        // Bigger text means fewer characters in the same width, so the column may
        // be wider before a line becomes tiring.
        assertEquals(612.dp, textColumnWidth(fontSizeSp = 18))
        assertEquals(816.dp, textColumnWidth(fontSizeSp = 24))
        assertTrue(textColumnWidth(fontSizeSp = 24) > textColumnWidth(fontSizeSp = 18))
    }

    @Test
    fun aTabletPageStaysWellUnderAHundredCharacters() {
        // Roughly half the font size per character: the column must not hold far
        // more than the 60-75 that read comfortably.
        val size = 18
        val charactersPerLine = textColumnWidth(size).value / (size / 2f)
        assertTrue(charactersPerLine < 80f, "a line would hold $charactersPerLine characters")
        assertTrue(charactersPerLine > 55f, "a line would hold only $charactersPerLine characters")
    }
}
