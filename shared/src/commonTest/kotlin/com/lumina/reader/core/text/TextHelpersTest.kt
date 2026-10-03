package com.lumina.reader.core.text

import com.lumina.reader.core.text.TestEncoders.utf16
import com.lumina.reader.core.text.TestEncoders.windows1251
import com.lumina.reader.core.text.charset.TextCharset
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** PercentCodec, Codepoints, the CharReaders and the number formats on every platform. */
class TextHelpersTest {

    @Test
    fun percentEncodingMatchesUrlEncoder() {
        assertEquals("%D0%BC%D0%B8%D1%80", PercentCodec.encodeComponent("мир"))
        assertEquals("a%26b%20c%2Bd", PercentCodec.encodeComponent("a&b c+d"))
        assertEquals("Az09-_.*%7E%21%27%28%29", PercentCodec.encodeComponent("Az09-_.*~!'()"))
        assertEquals("%F0%9F%98%80", PercentCodec.encodeComponent("😀"))
        assertEquals("%3F%3Fx", PercentCodec.encodeComponent("\uDC00\uD800x")) // lone surrogates
        assertEquals("%3Fx", PercentCodec.encodeComponent("\uD800x"))
        assertEquals("%C2%A0%E2%82%AC", PercentCodec.encodeComponent(" €"))
        assertEquals("", PercentCodec.encodeComponent(""))
    }

    @Test
    fun codepoints() {
        assertEquals("A", Codepoints.toString(0x41))
        assertEquals("😀", Codepoints.toString(0x1F600))
        assertEquals("\uD800", Codepoints.toString(0xD800))
        assertEquals("􏿿", Codepoints.toString(0x10FFFF))
        assertEquals("x😀", Codepoints.append(StringBuilder("x"), 0x1F600).toString())
        assertFailsWith<IllegalArgumentException> { Codepoints.toString(0x110000) }
        assertFailsWith<IllegalArgumentException> { Codepoints.toString(-1) }
    }

    private fun readAll(reader: CharReader, bufferSize: Int): String {
        val out = StringBuilder()
        val buffer = CharArray(bufferSize)
        while (true) {
            val read = reader.read(buffer, 0, buffer.size)
            if (read < 0) break
            out.appendRange(buffer, 0, read)
        }
        return out.toString()
    }

    @Test
    fun stringCharReader() {
        val text = "Привет 😀"
        assertEquals(text, readAll(StringCharReader(text), 3))
        assertEquals(text, readAll(StringCharReader(text), 100))
        assertEquals("", readAll(StringCharReader(""), 4))
        val reader = StringCharReader("ab")
        assertEquals(0, reader.read(CharArray(2), 0, 0))
    }

    @Test
    fun decodingCharReaderStreamsAnyCharset() {
        val text = "Глава 1. Привет, «мир» — 😀 ёж. ".repeat(3000)
        val cases = listOf(
            TextCharset.UTF_8 to text.encodeToByteArray(),
            TextCharset.UTF_16 to TestEncoders.bytes(0xFE, 0xFF) + utf16(text, bigEndian = true),
            TextCharset.UTF_16LE to utf16(text, bigEndian = false)
        )
        for ((charset, bytes) in cases) {
            for (chunk in listOf(1, 3, 4096, 64 * 1024)) {
                val reader = DecodingCharReader(Buffer().write(bytes), charset.newDecoder(), chunk)
                assertEquals(text, readAll(reader, 777), "${charset.name} / $chunk")
            }
        }
        val legacy = text.replace("😀", ":)").replace("«", "\"").replace("»", "\"")
        val cp1251 = assertNotNull(TextCharset.forName("cp1251"))
        val reader = DecodingCharReader(Buffer().write(windows1251(legacy)), cp1251.newDecoder(), 100)
        assertEquals(legacy, readAll(reader, 64))
        // Malformed tail: one replacement character at the end, then -1 for good.
        val broken = DecodingCharReader(Buffer().write(TestEncoders.bytes(0x41, 0xE2, 0x82)), TextCharset.UTF_8.newDecoder())
        assertEquals("A�", readAll(broken, 8))
        assertEquals(-1, broken.read(CharArray(4), 0, 4))
    }

    @Test
    fun numberFormatsOnEveryPlatform() {
        assertEquals("1.01", formatDecimal(1.005, 2))
        assertEquals("0.13", formatDecimal(0.125, 2))
        assertEquals("-0.0", formatDecimal(-0.0, 1))
        assertEquals("3", formatDecimal(2.5, 0))
        assertEquals("12,4", formatDecimal(12.35, 1, ','))
        assertEquals("0,00001", formatDecimal(1e-5, 5, ','))
        assertEquals("12345678,0", formatDecimal(1.2345678e7, 1, ','))
        assertEquals("100000000000000000000", formatDecimal(1e20, 0))
        assertEquals("NaN", formatDecimal(Double.NaN, 2))
        assertEquals("1 234 567", formatGrouped(1_234_567))
        assertEquals("-9 223 372 036 854 775 808", formatGrouped(Long.MIN_VALUE))
        assertEquals("999", formatGrouped(999))
    }
}
