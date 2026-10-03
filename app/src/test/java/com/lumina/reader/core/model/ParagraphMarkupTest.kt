package com.lumina.reader.core.model

import com.lumina.reader.core.model.ParagraphMarkup.BlockStyle
import com.lumina.reader.core.model.ParagraphMarkup.InlineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphMarkupTest {

    @Test
    fun plainParagraphIsUntouched() {
        val parsed = ParagraphMarkup.parse("Обычный абзац.")
        assertEquals(BlockStyle.NORMAL, parsed.style)
        assertEquals("Обычный абзац.", parsed.text)
        assertTrue(parsed.spans.isEmpty())
    }

    @Test
    fun blockStyleAndInlineSpansAreParsed() {
        val raw = ParagraphMarkup.block(
            BlockStyle.EPIGRAPH,
            "Он сказал: " + ParagraphMarkup.emphasis("тише") + " и " + ParagraphMarkup.strong("ушёл")
        )
        val parsed = ParagraphMarkup.parse(raw)
        assertEquals(BlockStyle.EPIGRAPH, parsed.style)
        assertEquals("Он сказал: тише и ушёл", parsed.text)
        assertEquals(2, parsed.spans.size)
        val emphasis = parsed.spans[0]
        assertEquals(InlineKind.EMPHASIS, emphasis.kind)
        assertEquals("тише", parsed.text.substring(emphasis.start, emphasis.end))
        val strong = parsed.spans[1]
        assertEquals(InlineKind.STRONG, strong.kind)
        assertEquals("ушёл", parsed.text.substring(strong.start, strong.end))
    }

    @Test
    fun noteReferenceKeepsLabelAndId() {
        val raw = "Слово" + ParagraphMarkup.noteRef("1", "n_1") + " дальше."
        val parsed = ParagraphMarkup.parse(raw)
        assertEquals("Слово1 дальше.", parsed.text)
        val note = parsed.spans.single()
        assertEquals(InlineKind.NOTE_REF, note.kind)
        assertEquals("n_1", note.noteId)
        assertEquals("1", parsed.text.substring(note.start, note.end))
        assertEquals("Слово1 дальше.", ParagraphMarkup.plainText(raw))
    }

    @Test
    fun unbalancedMarkersAreTolerated() {
        val raw = "${ParagraphMarkup.EMPHASIS_START}незакрыто${ParagraphMarkup.STRONG_END} конец" +
            "${ParagraphMarkup.NOTE_START}сломано"
        val parsed = ParagraphMarkup.parse(raw)
        assertEquals("незакрыто конецсломано", parsed.text)
        assertEquals(InlineKind.EMPHASIS, parsed.spans.single().kind)
        assertFalse(parsed.text.any(ParagraphMarkup::isMarker))
    }

    @Test
    fun imageParagraphsAreRecognised() {
        assertTrue(ParagraphMarkup.isImage("[IMG:cover.jpg]"))
        assertEquals("cover.jpg", ParagraphMarkup.imageId("[IMG:cover.jpg]"))
        assertEquals(null, ParagraphMarkup.imageId("[IMG:broken"))
    }
}
