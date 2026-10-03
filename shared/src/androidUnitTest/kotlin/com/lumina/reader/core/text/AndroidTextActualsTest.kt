package com.lumina.reader.core.text

import com.lumina.reader.core.parser.common.TextEncoding
import com.lumina.reader.core.parser.common.detectStream
import com.lumina.reader.core.text.charset.PlatformCharsets
import com.lumina.reader.core.text.charset.TextCharset
import com.lumina.reader.core.text.charset.toJavaCharset
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import kotlin.random.Random

/**
 * The Android actuals are java.nio / java.net as before the move; these tests
 * pin that, and compare the common implementations the iPhone build uses
 * (PercentCodec, Rfc3986) with the JVM classes they stand in for.
 */
class AndroidTextActualsTest {

    private val random = Random(7)

    @Test
    fun charsetNamesResolveThroughJavaNio() {
        val names = listOf(
            "UTF-8", "utf8", "UTF-16", "unicode", "windows-1251", "CP1251", "koi8-r", "KOI8-U", "cp866",
            "ISO-8859-5", "latin1", "x-MacCyrillic", "Shift_JIS", "GBK", "Big5", "windows-1250", "EUC-JP"
        )
        for (name in names) {
            val charset = TextCharset.forName(name)
            assertEquals(name, Charset.forName(name).name(), charset?.name)
            assertEquals(name, Charset.forName(name), charset?.toJavaCharset())
        }
        for (bad in listOf("", "x-no-such-charset", "windows 1251", "bad\u0000name")) assertNull(bad, TextCharset.forName(bad))
    }

    @Test
    fun androidDecodingIsJavaNio() {
        val samples = listOf(
            "Привет, мир! 😀".toByteArray(Charsets.UTF_8),
            ByteArray(500) { random.nextInt(256).toByte() },
            byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0xF0.toByte())
        )
        for (name in listOf("UTF-8", "UTF-16", "UTF-16LE", "windows-1251", "Shift_JIS", "GBK")) {
            val charset = Charset.forName(name)
            for (bytes in samples) {
                val expected = String(bytes, charset)
                assertEquals(expected, PlatformCharsets.decodeWhole(bytes, 0, bytes.size, name))
                assertEquals(expected, TextCharset.forName(name)!!.decode(bytes))
                // The streaming adapter in chunks of 0..6 bytes.
                val decoder = PlatformCharsets.decoderFor(name)!!
                val out = StringBuilder()
                var position = 0
                while (position < bytes.size) {
                    val length = minOf(bytes.size - position, random.nextInt(0, 7))
                    decoder.decode(bytes, position, length, out)
                    position += length
                }
                decoder.flush(out)
                assertEquals("$name chunked", expected, out.toString())
            }
        }
        assertNull(PlatformCharsets.decoderFor("x-no-such-charset"))
        assertNull(PlatformCharsets.decodeWhole(ByteArray(1), 0, 1, "x-no-such-charset"))
    }

    @Test
    fun streamDetectionReadsLikeTheSourceVariant() {
        val inputs = listOf(
            "Глава. ".repeat(20_000).toByteArray(Charsets.UTF_8),
            "Глава. ".repeat(20_000).toByteArray(Charset.forName("windows-1251")),
            "<?xml version=\"1.0\" encoding=\"koi8-r\"?>".toByteArray() + "Привет".toByteArray(Charset.forName("KOI8-R")),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, 0x3C),
            ByteArray(0)
        )
        for (input in inputs) {
            val fromStream = TextEncoding.detectStream({ ByteArrayInputStream(input) })
            assertEquals(TextEncoding.detect({ Buffer().write(input) }), fromStream)
        }
    }

    @Test
    fun readerAdapter() {
        val reader = StringReader("Привет").asCharReader()
        val buffer = CharArray(4)
        assertEquals(4, reader.read(buffer, 0, 4))
        assertEquals("Прив", String(buffer))
        assertEquals(2, reader.read(buffer, 0, 4))
        assertEquals(-1, reader.read(buffer, 0, 4))
    }

    @Test
    fun percentEncodingMatchesUrlEncoderForEveryCharacter() {
        val all = buildString { for (c in 0..0xFFFF) append(c.toChar()) }
        for (chunk in all.chunked(97)) {
            assertEquals(URLEncoder.encode(chunk, "UTF-8").replace("+", "%20"), PercentCodec.encodeComponent(chunk))
        }
        val pool = "aZ9 -_.*~+&=?/%# ёЁ€😀𐀀􏿿"
        repeat(5000) {
            val text = buildString { repeat(random.nextInt(0, 20)) { append(pool[random.nextInt(pool.length)]) } }
            assertEquals(text, URLEncoder.encode(text, "UTF-8").replace("+", "%20"), PercentCodec.encodeComponent(text))
        }
    }

    @Test
    fun urlActualsAreJavaNetUri() {
        val uri = URI("https://Host.example:8443/a%20b/c?x=1")
        val parts = parseUrl("https://Host.example:8443/a%20b/c?x=1")!!
        assertEquals(uri.scheme, parts.scheme)
        assertEquals(uri.host, parts.host)
        assertEquals(uri.port, parts.port)
        assertEquals(uri.rawPath, parts.rawPath)
        assertEquals(uri.rawQuery, parts.rawQuery)
        assertNull(parseUrl("a b"))
        assertEquals("https://host/x", resolveUrl("https://host", "x"))
        assertNull(resolveUrl("https://host/", "a b"))
    }

    /**
     * Where OPDS feeds and browsers agree, RFC 3986 (iOS) and java.net.URI
     * (Android) resolve alike. Known differences are left out: RFC 2396 keeps
     * ".." above the root and does not re-normalise absolute references.
     */
    @Test
    fun rfc3986AgreesWithJavaNetUriOnOpdsStyleLinks() {
        val bases = listOf(
            "https://flibusta.is/opds/new/0/new", "https://host", "https://host/", "http://h:8080/a/b/c.xml?x=1",
            "https://coollib.net/opds/search?q=%D0%BC", "https://h/книги/список"
        )
        val references = listOf(
            "/b/1/fb2", "1", "./y", "//cdn.example.org/a.jpg", "http://other.org/feed", "a%20b.epub", "sub/dir/",
            "/opds/search?searchTerm=%7BsearchTerms%7D", "g;x?y", "книга.fb2", "/i/12/cover.jpg", "list?page=2&x=3"
        )
        for (base in bases) {
            for (reference in references) {
                assertEquals("$base + $reference", resolveUrl(base, reference), Rfc3986.resolve(base, reference))
            }
        }
        // ".." agrees as long as it stays below the root.
        for (base in listOf("https://flibusta.is/opds/new/0/new", "http://h:8080/a/b/c.xml?x=1")) {
            for (reference in listOf("../x", "../../x", "../", "./../y/z")) {
                assertEquals("$base + $reference", resolveUrl(base, reference), Rfc3986.resolve(base, reference))
            }
        }
        assertEquals("https://host/../x", resolveUrl("https://host", "../x")) // RFC 2396
        assertEquals("https://host/x", Rfc3986.resolve("https://host", "../x")) // RFC 3986
    }
}
