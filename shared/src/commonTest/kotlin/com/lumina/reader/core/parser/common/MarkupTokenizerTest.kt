package com.lumina.reader.core.parser.common

import com.lumina.reader.core.text.CharReader
import com.lumina.reader.core.text.StringCharReader
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tokenizer, entity and Base64 tests (moved from app's ParserSupportTest). */
class MarkupTokenizerTest {

    private fun tokens(text: String, raw: Set<String> = emptySet()): List<MarkupToken> {
        val tokenizer = MarkupTokenizer(StringCharReader(text), raw)
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
        for ((name, value) in expected) assertEquals(value, HtmlEntities.decode(name), name)
        assertNull(HtmlEntities.decode("unknownentity"))
        assertEquals("�", HtmlEntities.decode("#xD800"))
        assertEquals("�", HtmlEntities.decode("#x110000"))
        assertEquals("�", HtmlEntities.decode("#0"))
        assertNull(HtmlEntities.decode("#x"))
        assertNull(HtmlEntities.decode("#1234567890"))
        assertEquals("é", HtmlEntities.decode("EACUTE")) // unknown case falls back to lower case
        assertEquals("a &lt; b — c", HtmlEntities.decodeAll("a &amp;lt; b &mdash; c"))
    }

    @Test
    fun tokenizerIsForgiving() {
        val result = tokens(
            "<!DOCTYPE html [<!ENTITY x 'y'>]><!-- c --><?pi x?><p class=a id='b' data-x=\"&quot;q&quot;\">" +
                "1 &lt; 2 &bogus; <3<br/><![CDATA[<raw>]]></P><script>if (a < b) {}</script>end"
        )
        val start = result[0] as MarkupToken.StartTag
        assertEquals("p", start.name)
        assertEquals("a", start.attr("class"))
        assertEquals("b", start.attr("id"))
        assertEquals("\"q\"", start.attr("data-x"))
        val texts = result.filterIsInstance<MarkupToken.Text>().joinToString("") { it.text }
        assertTrue(texts.startsWith("1 < 2 &bogus; <3<raw>"), texts)
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
        val tokenizer = MarkupTokenizer(StringCharReader("<p>text<b class=\"x"))
        while (tokenizer.next() != null) Unit
        assertTrue(tokenizer.truncated)
    }

    @Test
    fun tinyReadsGiveTheSameTokens() {
        val text = "<?xml version=\"1.0\"?><FictionBook><body><section><p>Привет, &laquo;мир&raquo;!</p>" +
            "<p><![CDATA[a<b]]> &#x1F600; конец</p><!-- --- --></section></body></FictionBook>"
        val whole = tokens(text).map(::describe)
        val source = StringCharReader(text)
        // At most two characters per read: every construct straddles reads.
        val tokenizer = MarkupTokenizer(CharReader { buffer, offset, length -> source.read(buffer, offset, minOf(length, 2)) })
        val split = generateSequence { tokenizer.next() }.map(::describe).toList()
        assertEquals(whole, split)
        assertTrue(whole.contains("T:Привет, «мир»!"))
    }

    private fun describe(token: MarkupToken): String = when (token) {
        is MarkupToken.StartTag -> "S:${token.name}${token.attributes}${token.selfClosing}"
        is MarkupToken.EndTag -> "E:${token.name}"
        is MarkupToken.Text -> "T:${token.text}"
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun base64StreamDecoding() {
        val data = ByteArray(10_000) { (it % 256).toByte() }
        val encoded = Base64.Mime.encode(data)
        val decoder = Base64StreamDecoder(1_000_000)
        encoded.chunked(333).forEach { decoder.feed(it) }
        assertContentEquals(data, decoder.result())

        val urlSafe = Base64StreamDecoder(1_000)
        urlSafe.feed(Base64.UrlSafe.encode(byteArrayOf(-5, -1, -2, 0, 1)))
        assertContentEquals(byteArrayOf(-5, -1, -2, 0, 1), urlSafe.result())

        val capped = Base64StreamDecoder(100)
        capped.feed(encoded)
        assertTrue(capped.overflow)
        assertNull(capped.result())
        assertNull(Base64StreamDecoder(100).result())
    }
}
