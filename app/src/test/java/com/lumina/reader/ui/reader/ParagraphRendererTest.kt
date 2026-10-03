@file:OptIn(ExperimentalTextApi::class)

package com.lumina.reader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ParagraphMarkup.BlockStyle
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReaderTextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParagraphRendererTest {

    private val typography = ReaderTypography(
        fontSizeSp = 18,
        lineSpacing = 1.5f,
        fontFamilyName = "Serif",
        justify = true,
        hyphenation = true,
        firstLineIndentEm = 1.5f,
        bionic = false,
        localeTag = null
    )
    private val colors = ReaderTextColors(
        text = Color.Black,
        noteRef = Color.Blue,
        searchMatch = Color.Green
    )

    @Test
    fun textIsThePlainText() {
        val raw = "Он " + ParagraphMarkup.emphasis("тихо") + " сказал" + ParagraphMarkup.noteRef("1", "n1")
        val rendered = renderParagraph(raw, typography, colors)
        assertEquals(ParagraphMarkup.plainText(raw), rendered.text.text)
        assertEquals("Он тихо сказал1", rendered.text.text)
    }

    @Test
    fun inlineMarkupBecomesSpans() {
        val raw = ParagraphMarkup.emphasis("курсив") + " и " + ParagraphMarkup.strong("жирный")
        val spans = renderParagraph(raw, typography, colors).text.spanStyles
        val italic = spans.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals(0 to 6, italic.start to italic.end)
        val bold = spans.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals(9 to 15, bold.start to bold.end)
    }

    @Test
    fun noteReferenceIsSuperscriptAndClickable() {
        val raw = "Слово" + ParagraphMarkup.noteRef("12", "note_12")
        var clicked: String? = null
        val rendered = renderParagraph(raw, typography, colors, onNoteClick = { clicked = it })
        val note = rendered.text.spanStyles.single { it.item.baselineShift == BaselineShift.Superscript }
        assertEquals(5 to 7, note.start to note.end)
        assertEquals(Color.Blue, note.item.color)

        val link = rendered.text.getLinkAnnotations(0, rendered.text.length).single()
        assertEquals(5 to 7, link.start to link.end)
        val clickable = link.item as LinkAnnotation.Clickable
        assertEquals(NOTE_LINK_TAG_PREFIX + "note_12", clickable.tag)
        clickable.linkInteractionListener?.onClick(clickable)
        assertEquals("note_12", clicked)

        // Measuring passes no click handler: same text and spans, no link.
        val measured = renderParagraph(raw, typography, colors)
        assertTrue(measured.text.getLinkAnnotations(0, measured.text.length).isEmpty())
        assertEquals(rendered.text.text, measured.text.text)
    }

    @Test
    fun ordinaryParagraphFollowsTypographySettings() {
        val justified = renderParagraph("Текст", typography, colors).style
        assertEquals(TextAlign.Justify, justified.textAlign)
        assertEquals(TextIndent(firstLine = 1.5.em), justified.textIndent)
        assertEquals(Hyphens.Auto, justified.hyphens)
        assertEquals(18.sp, justified.fontSize)
        assertEquals(1.5.em, justified.lineHeight)

        val ragged = renderParagraph(
            "Текст",
            typography.copy(justify = false, hyphenation = false, firstLineIndentEm = 0f),
            colors
        ).style
        assertEquals(TextAlign.Start, ragged.textAlign)
        assertNull(ragged.textIndent)
        assertEquals(Hyphens.None, ragged.hyphens)
    }

    @Test
    fun blockStylesHaveTheirOwnLayout() {
        fun render(style: BlockStyle) = renderParagraph(ParagraphMarkup.block(style, "Строка"), typography, colors)

        val subtitle = render(BlockStyle.SUBTITLE)
        assertEquals(TextAlign.Center, subtitle.style.textAlign)
        assertEquals(FontWeight.SemiBold, subtitle.style.fontWeight)
        assertNull(subtitle.style.textIndent)

        val verse = render(BlockStyle.VERSE)
        assertEquals(TextAlign.Start, verse.style.textAlign)
        assertNull(verse.style.textIndent)
        assertTrue(verse.startIndentEm > 0f)

        val epigraph = render(BlockStyle.EPIGRAPH)
        assertEquals(FontStyle.Italic, epigraph.style.fontStyle)
        assertEquals(2f, epigraph.startIndentEm)
        assertEquals(1f, epigraph.endIndentEm)
        assertTrue(epigraph.style.fontSize.value < 18f)

        val author = render(BlockStyle.TEXT_AUTHOR)
        assertEquals(TextAlign.End, author.style.textAlign)
        assertEquals(FontStyle.Italic, author.style.fontStyle)

        val heading = render(BlockStyle.HEADING)
        assertEquals(FontWeight.Bold, heading.style.fontWeight)
        assertEquals(20.sp, heading.style.fontSize)
        assertEquals("Строка", heading.text.text)
    }

    @Test
    fun bionicBoldPrefixesMergeWithMarkup() {
        val raw = ParagraphMarkup.emphasis("медленно") + " читать"
        val rendered = renderParagraph(raw, typography.copy(bionic = true), colors)
        val spans = rendered.text.spanStyles
        assertTrue(spans.any { it.item.fontStyle == FontStyle.Italic && it.start == 0 && it.end == 8 })
        // "медленно" (8 letters) gets a 3-letter bold prefix, "читать" (6) gets 3.
        assertTrue(spans.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 && it.end == 3 })
        assertTrue(spans.any { it.item.fontWeight == FontWeight.Bold && it.start == 9 && it.end == 12 })
        assertEquals("медленно читать", rendered.text.text)
    }

    @Test
    fun highlightsAndSearchMatchAreBackgrounds() {
        val rendered = renderParagraph(
            raw = "Найти здесь цитату",
            typography = typography,
            colors = colors,
            highlights = listOf(HighlightSpan(6, 11, Color.Yellow), HighlightSpan(40, 50, Color.Red)),
            searchMatch = OffsetRange(12, 18)
        )
        val backgrounds = rendered.text.spanStyles.filter { it.item.background != Color.Unspecified }
        assertEquals(2, backgrounds.size)
        val highlight = backgrounds.single { it.start == 6 }
        assertEquals(11, highlight.end)
        assertEquals(Color.Yellow.copy(alpha = HIGHLIGHT_ALPHA), highlight.item.background)
        val match = backgrounds.single { it.start == 12 }
        assertEquals(Color.Green, match.item.background)
    }

    @Test
    fun highlightColorsAreParsed() {
        assertEquals(Color(0xFFFFEB3B), parseHighlightColor("#FFEB3B"))
        assertEquals(Color(0x80112233), parseHighlightColor("#80112233"))
        assertEquals(DEFAULT_HIGHLIGHT_COLOR, parseHighlightColor("жёлтый"))
    }

    @Test
    fun cyrillicTextGetsRussianHyphenation() {
        assertEquals("ru", detectTextLocaleTag("Мой дядя самых честных правил"))
        assertNull(detectTextLocaleTag("It was a bright cold day in April"))
        assertNull(detectTextLocaleTag("12345"))
    }

    @Test
    fun settingsMapToTypography() {
        val settings = ReaderSettings(
            fontSizeSp = 21,
            textAlign = ReaderTextAlign.START,
            hyphenation = false,
            firstLineIndentEm = 2f,
            isBionicReadingEnabled = true
        )
        val mapped = settings.toTypography("ru")
        assertEquals(21, mapped.fontSizeSp)
        assertEquals(false, mapped.justify)
        assertEquals(false, mapped.hyphenation)
        assertEquals(2f, mapped.firstLineIndentEm)
        assertEquals(true, mapped.bionic)
        assertEquals("ru", mapped.localeTag)
    }

    @Test
    fun unknownFontsFallBackToSerif() {
        assertEquals(readerFontFamily("Serif"), readerFontFamily("Cursive"))
        assertEquals(readerFontFamily("Serif"), readerFontFamily(""))
        assertTrue(readerFontFamily("SansSerif") != readerFontFamily("Serif"))
    }
}
