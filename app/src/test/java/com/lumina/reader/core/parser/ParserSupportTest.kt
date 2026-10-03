package com.lumina.reader.core.parser

import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.parser.common.MarkupTokenizer
import com.lumina.reader.core.parser.common.NaturalOrderComparator
import com.lumina.reader.core.parser.common.NoteSupport
import com.lumina.reader.core.parser.common.ParagraphAccumulator
import com.lumina.reader.core.parser.epub.EpubPaths
import com.lumina.reader.core.text.StringCharReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Tokenizer, entity, encoding and Base64 tests moved to :shared commonTest (stage 4).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParserSupportTest {

    @Test
    fun paragraphAccumulatorCollapsesSpacesAndKeepsSpansTight() {
        val acc = ParagraphAccumulator()
        acc.appendText("  Hello \n  ")
        acc.beginEmphasis()
        acc.appendText(" world ")
        acc.endEmphasis()
        acc.appendText(" again­! ")
        val raw = acc.take()!!
        val parsed = ParagraphMarkup.parse(raw)
        assertEquals("Hello world again!", parsed.text)
        assertEquals("world", parsed.text.substring(parsed.spans[0].start, parsed.spans[0].end))
        assertNull(acc.take())
    }

    @Test
    fun naturalOrderAndPaths() {
        val sorted = listOf("chapter10.xhtml", "chapter2.xhtml", "Chapter1.xhtml", "chapter2a.xhtml")
            .sortedWith(NaturalOrderComparator)
        assertEquals(listOf("Chapter1.xhtml", "chapter2.xhtml", "chapter2a.xhtml", "chapter10.xhtml"), sorted)

        assertEquals("OEBPS/Text/Chapter 1.xhtml" to "a%b", EpubPaths.resolve("OEBPS/Text/x.xhtml", "Chapter%201.xhtml#a%25b"))
        assertEquals("OEBPS/Images/Обложка.jpg" to null, EpubPaths.resolve("OEBPS/Text/x.xhtml", "../Images/%D0%9E%D0%B1%D0%BB%D0%BE%D0%B6%D0%BA%D0%B0.jpg"))
        assertEquals("OEBPS/Text/x.xhtml" to "n1", EpubPaths.resolve("OEBPS/Text/x.xhtml", "#n1"))
    }

    @Test
    fun randomMalformedMarkupNeverThrows() {
        val fragments = listOf(
            "<p>", "</p>", "<br/>", "<br>", "&amp;", "&", "&nbsp", "<", ">", "<a href='#n1'>", "<a href=\"x.html#n2\">",
            "</a>", "<em>", "</i>", "<b>", "</strong>", "текст ", "1", "[2]", "<!--", "-->", "<![CDATA[", "]]>", "<sup>",
            "</sup>", "<aside epub:type='footnote' id='n1'>", "</aside>", "\"", "'", "<img src='x.png'>", "<h1>", "</h2>",
            "<table><tr><td>", "</td><td>", "<div class=\"poem stanza\">", "</div>", "<hr/>", "* * *", "<li>", "<ol>",
            "<section>", "</section>", "<title>", "</title>", "<poem>", "<stanza>", "<v>", "</v>", "<binary id='b'>QUJD",
            "</binary>", "<body name=\"notes\">", "</body>", "<epigraph>", "<subtitle>", "<image l:href='#b'/>", "<?pi",
            "<!DOCTYPE x [", "]>", "\u0000", "﻿", ""
        )
        val random = java.util.Random(7)
        repeat(300) {
            val text = buildString { repeat(random.nextInt(80)) { append(fragments[random.nextInt(fragments.size)]) } }
            com.lumina.reader.core.parser.epub.XhtmlExtractor("a/b.xhtml", com.lumina.reader.core.parser.epub.EpubNotes()) { it }
                .extract(text)
            val builder = com.lumina.reader.core.parser.fb2.Fb2BookBuilder("x.fb2", false)
            val tokenizer = MarkupTokenizer(StringCharReader("<FictionBook><body><section>$text"))
            while (true) builder.accept(tokenizer.next() ?: break)
            builder.build(damaged = tokenizer.truncated)
            com.lumina.reader.core.parser.txt.TxtLayout.split(text)
        }
    }

    @Test
    fun noteHelpers() {
        assertTrue(NoteSupport.looksLikeNoteLabel("[12]"))
        assertTrue(NoteSupport.looksLikeNoteLabel("*"))
        assertFalse(NoteSupport.looksLikeNoteLabel("Глава 1"))
        assertEquals("12", NoteSupport.cleanLabel(" [12] "))
        val raw = "a" + ParagraphMarkup.noteRef("1", "known") + "b" + ParagraphMarkup.noteRef("2", "missing")
        assertEquals("a" + ParagraphMarkup.noteRef("1", "known") + "b2", NoteSupport.dropUnknownRefs(raw) { it == "known" })
        assertEquals("Текст", NoteSupport.stripLeadingLabel("[1] Текст", "1"))
        assertEquals("1812 год", NoteSupport.stripLeadingLabel("1812 год", "1"))
    }
}
