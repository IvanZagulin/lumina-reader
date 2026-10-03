package com.lumina.reader.core.parser

import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.parser.common.Base64StreamDecoder
import com.lumina.reader.core.parser.common.HtmlEntities
import com.lumina.reader.core.parser.common.MarkupToken
import com.lumina.reader.core.parser.common.MarkupTokenizer
import com.lumina.reader.core.parser.common.NaturalOrderComparator
import com.lumina.reader.core.parser.common.NoteSupport
import com.lumina.reader.core.parser.common.ParagraphAccumulator
import com.lumina.reader.core.parser.common.TextEncoding
import com.lumina.reader.core.parser.epub.EpubPaths
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader
import java.nio.charset.Charset
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParserSupportTest {

    private fun tokens(text: String, raw: Set<String> = emptySet()): List<MarkupToken> {
        val tokenizer = MarkupTokenizer(StringReader(text), raw)
        return generateSequence { tokenizer.next() }.toList()
    }

    @Test
    fun entities() {
        assertTrue(HtmlEntities.namedCount >= 200)
        val expected = mapOf(
            "hellip" to "…", "shy" to "­", "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”",
            "laquo" to "«", "raquo" to "»", "mdash" to "—", "ndash" to "–", "nbsp" to " ", "copy" to "©",
            "times" to "×", "minus" to "−", "bdquo" to "„", "euro" to "€", "Agrave" to "À", "agrave" to "à",
            "#x1F600" to "😀", "#128512" to "😀", "#151" to "—", "#1093" to "х"
        )
        for ((name, value) in expected) assertEquals(name, value, HtmlEntities.decode(name))
        assertNull(HtmlEntities.decode("unknownentity"))
        assertEquals("�", HtmlEntities.decode("#xD800"))
        assertEquals("a &lt; b — c", HtmlEntities.decodeAll("a &amp;lt; b &mdash; c"))
    }

    @Test
    fun tokenizerIsForgiving() {
        val result = tokens("<!DOCTYPE html [<!ENTITY x 'y'>]><!-- c --><?pi x?><p class=a id='b' data-x=\"&quot;q&quot;\">1 &lt; 2 &bogus; <3<br/><![CDATA[<raw>]]></P><script>if (a < b) {}</script>end")
        val start = result[0] as MarkupToken.StartTag
        assertEquals("p", start.name)
        assertEquals("a", start.attr("class"))
        assertEquals("b", start.attr("id"))
        assertEquals("\"q\"", start.attr("data-x"))
        val texts = result.filterIsInstance<MarkupToken.Text>().joinToString("") { it.text }
        assertTrue(texts, texts.startsWith("1 < 2 &bogus; <3<raw>"))
        assertTrue(texts.endsWith("if (a < b) {}end") || texts.endsWith("end"))
        assertTrue(result.any { it is MarkupToken.StartTag && it.name == "br" && it.selfClosing })
        assertTrue(result.any { it is MarkupToken.EndTag && it.name == "p" })

        val skipped = tokens("<style>p{}</style><p>x</p>", setOf("style"))
        assertEquals(listOf("x"), skipped.filterIsInstance<MarkupToken.Text>().map { it.text })

        val namespaced = tokens("<image xlink:href=\"#a\"/><a l:href=\"#n\" type=\"note\">1</a>")
        assertEquals("#a", (namespaced[0] as MarkupToken.StartTag).hrefAttr())
        assertEquals("#n", (namespaced[1] as MarkupToken.StartTag).hrefAttr())
        assertEquals("#n", (namespaced[1] as MarkupToken.StartTag).attrByLocal("href"))
    }

    @Test
    fun truncatedInputIsReported() {
        val tokenizer = MarkupTokenizer(StringReader("<p>text<b class=\"x"))
        while (tokenizer.next() != null) Unit
        assertTrue(tokenizer.truncated)
    }

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
    fun encodingDetection() {
        assertTrue(TextEncoding.isValidUtf8("😀 Привет".toByteArray(Charsets.UTF_8)))
        assertFalse(TextEncoding.isValidUtf8("Привет".toByteArray(Charset.forName("windows-1251"))))
        assertFalse(TextEncoding.isValidUtf8(byteArrayOf(0xC0.toByte(), 0x80.toByte()))) // overlong
        assertEquals(
            Charset.forName("windows-1251"),
            TextEncoding.declaredCharset("<?xml version=\"1.0\" encoding=\"windows-1251\"?><a/>".toByteArray())
        )
        assertEquals("Тест", TextEncoding.decode("Тест".toByteArray(Charset.forName("windows-1251"))))
        assertEquals("Тест", TextEncoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Тест".toByteArray()))
    }

    @Test
    fun base64StreamDecoding() {
        val data = ByteArray(10_000) { (it % 256).toByte() }
        val encoded = Base64.getMimeEncoder().encodeToString(data)
        val decoder = Base64StreamDecoder(1_000_000)
        encoded.chunked(333).forEach { decoder.feed(it) }
        assertArrayEquals(data, decoder.result())

        val capped = Base64StreamDecoder(100)
        capped.feed(encoded)
        assertTrue(capped.overflow)
        assertNull(capped.result())
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
            val tokenizer = MarkupTokenizer(StringReader("<FictionBook><body><section>$text"))
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
