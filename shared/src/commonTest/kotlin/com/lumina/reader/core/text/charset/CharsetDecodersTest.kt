package com.lumina.reader.core.text.charset

import com.lumina.reader.core.text.TestEncoders.bytes
import com.lumina.reader.core.text.TestEncoders.koi8r
import com.lumina.reader.core.text.TestEncoders.utf16
import com.lumina.reader.core.text.TestEncoders.windows1251
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The common decoders against fixed expectations (the java.nio results; the
 * JVM parity tests in androidUnitTest compare them with java.nio directly).
 * Runs on the JVM and on the iOS simulator.
 */
class CharsetDecodersTest {

    private val r = "�"

    private fun utf8(vararg values: Int) = Utf8Decoder().decodeAll(bytes(*values))

    private fun utf16be(vararg values: Int) = Utf16Decoder(Utf16Decoder.Mode.BIG_ENDIAN).decodeAll(bytes(*values))

    private fun utf16le(vararg values: Int) = Utf16Decoder(Utf16Decoder.Mode.LITTLE_ENDIAN).decodeAll(bytes(*values))

    private fun utf16(vararg values: Int) = Utf16Decoder(Utf16Decoder.Mode.DETECT).decodeAll(bytes(*values))

    @Test
    fun utf8DecodesWellFormedText() {
        val text = "Привет, мир! ẞ € 😀 \u0000\u007F\u0080߿ࠀ￿"
        assertEquals(text, Utf8Decoder().decodeAll(text.encodeToByteArray()))
        assertEquals("😀", utf8(0xF0, 0x9F, 0x98, 0x80))
        assertEquals("􏿿", utf8(0xF4, 0x8F, 0xBF, 0xBF))
    }

    @Test
    fun utf8ReplacesMalformedInputLikeTheJdk() {
        assertEquals("A${r}B", utf8(0x41, 0x80, 0x42))
        assertEquals("$r$r", utf8(0xC0, 0xAF)) // overlong two-byte form
        assertEquals("$r$r", utf8(0xE0, 0x80)) // E0 needs A0..BF
        assertEquals("$r$r$r", utf8(0xE0, 0x80, 0x80))
        assertEquals(r, utf8(0xED, 0xA0, 0x80)) // encoded surrogate: one replacement
        assertEquals("${r}A", utf8(0xE2, 0x82, 0x41))
        assertEquals("${r}A", utf8(0xF0, 0x90, 0x41))
        assertEquals("${r}A", utf8(0xF0, 0x90, 0x80, 0x41))
        assertEquals("$r$r$r$r", utf8(0xF4, 0x90, 0x80, 0x80)) // above U+10FFFF
        assertEquals("$r$r$r$r", utf8(0xF0, 0x80, 0x80, 0x80)) // overlong four-byte form
        assertEquals("$r$r", utf8(0xF8, 0x88))
        assertEquals(r, utf8(0xE2, 0x82)) // cut off at the end
        assertEquals(r, utf8(0xF0, 0x9F, 0x98))
        assertEquals("A$r", utf8(0x41, 0xC3))
        assertEquals("", utf8())
    }

    @Test
    fun utf16FollowsTheJdkRules() {
        assertEquals("A😀", utf16be(0x00, 0x41, 0xD8, 0x3D, 0xDE, 0x00))
        assertEquals("A😀", utf16le(0x41, 0x00, 0x3D, 0xD8, 0x00, 0xDE))
        assertEquals("﻿A", utf16be(0xFE, 0xFF, 0x00, 0x41)) // explicit byte order: the mark is text
        assertEquals("￾䄀", utf16be(0xFF, 0xFE, 0x41, 0x00)) // so is a reversed one
        assertEquals("A", utf16(0xFE, 0xFF, 0x00, 0x41))
        assertEquals("A", utf16(0xFF, 0xFE, 0x41, 0x00))
        assertEquals("A", utf16(0x00, 0x41)) // no mark: big-endian
        assertEquals("A﻿", utf16(0xFE, 0xFF, 0x00, 0x41, 0xFE, 0xFF))
        assertEquals("A￾", utf16(0xFE, 0xFF, 0x00, 0x41, 0xFF, 0xFE))
        assertEquals(r, utf16be(0xD8, 0x00, 0x00, 0x41)) // high surrogate + non-low: both units
        assertEquals("${r}A", utf16be(0xDC, 0x00, 0x00, 0x41)) // lone low surrogate
        assertEquals("A$r", utf16be(0x00, 0x41, 0x00)) // odd trailing byte
        assertEquals("A$r", utf16be(0x00, 0x41, 0xD8, 0x00)) // unfinished pair
        assertEquals(r, utf16be(0xD8, 0x00, 0xDC)) // unfinished pair + odd byte: one replacement
        assertEquals("", utf16(0xFE, 0xFF))
    }

    @Test
    fun singleByteTables() {
        val text = "Съешь же ещё этих мягких французских булок, да выпей чаю. ЁЖИК №1"
        assertEquals(text, SingleByteDecoder(SingleByteTables.WINDOWS_1251).decodeAll(windows1251(text)))
        val koiText = text.replace("№", "N") // KOI8-R has no numero sign
        assertEquals(koiText, SingleByteDecoder(SingleByteTables.KOI8_R).decodeAll(koi8r(koiText)))
        assertEquals("Ђ$r", SingleByteDecoder(SingleByteTables.WINDOWS_1251).decodeAll(bytes(0x80, 0x98)))
        assertEquals("€$r", SingleByteDecoder(SingleByteTables.WINDOWS_1252).decodeAll(bytes(0x80, 0x81)))
        assertEquals("А", SingleByteDecoder(SingleByteTables.IBM866).decodeAll(bytes(0x80)))
        assertEquals("Ф", SingleByteDecoder(SingleByteTables.ISO_8859_5).decodeAll(bytes(0xC4)))
        assertEquals("ю", SingleByteDecoder(SingleByteTables.KOI8_U).decodeAll(bytes(0xC0)))
        assertEquals("А†", SingleByteDecoder(SingleByteTables.X_MAC_CYRILLIC).decodeAll(bytes(0x80, 0xA0)))
        assertEquals("A$r", SingleByteDecoder(SingleByteTables.US_ASCII).decodeAll(bytes(0x41, 0xC1)))
        assertEquals("ÿ", SingleByteDecoder(SingleByteTables.ISO_8859_1).decodeAll(bytes(0xFF)))
        for (table in listOf(SingleByteTables.WINDOWS_1251, SingleByteTables.KOI8_R, SingleByteTables.ISO_8859_1)) {
            assertEquals(128, table.length)
        }
    }

    @Test
    fun registryResolvesNamesLikeJavaNio() {
        assertEquals("windows-1251", CharsetRegistry.canonicalName("CP1251"))
        assertEquals("windows-1251", CharsetRegistry.canonicalName("Windows-1251"))
        assertEquals("KOI8-R", CharsetRegistry.canonicalName("koi8"))
        assertEquals("IBM866", CharsetRegistry.canonicalName("866"))
        assertEquals("ISO-8859-1", CharsetRegistry.canonicalName("latin1"))
        assertEquals("UTF-8", CharsetRegistry.canonicalName("utf8"))
        assertEquals("UTF-16", CharsetRegistry.canonicalName("unicode"))
        assertEquals("x-MacCyrillic", CharsetRegistry.canonicalName("maccyrillic"))
        assertNull(CharsetRegistry.canonicalName("windows-1251 "))
        assertNull(CharsetRegistry.canonicalName("cp-1251"))
        // Every name (case-insensitive) belongs to exactly one charset.
        val owners = CharsetRegistry.entries
            .flatMap { entry -> (listOf(entry.canonicalName) + entry.aliases).map { it.lowercase() to entry.canonicalName } }
            .groupBy({ it.first }, { it.second })
        for ((name, charsets) in owners) assertEquals(1, charsets.toSet().size, name)
    }

    @Test
    fun platformCharsetsDecodeTheRegisteredCharsets() {
        val text = "Привет, мир! 😀"
        for (entry in CharsetRegistry.entries) {
            val charset = assertNotNull(TextCharset.forName(entry.canonicalName), entry.canonicalName)
            assertEquals(entry.canonicalName, charset.name)
            for (alias in entry.aliases) assertEquals(charset, TextCharset.forName(alias), alias)
        }
        assertEquals(text, TextCharset.UTF_8.decode(text.encodeToByteArray()))
        assertEquals(text, TextCharset.UTF_16.decode(bytes(0xFF, 0xFE) + utf16(text, bigEndian = false)))
        assertEquals("ривет", TextCharset.UTF_8.decode(text.encodeToByteArray(), 2, 10))
        assertNull(TextCharset.forName("x-no-such-charset"))
        assertNull(TextCharset.forName(""))
    }

    @Test
    fun chunkedDecodingEqualsWholeDecoding() {
        val random = Random(4)
        val samples = listOf(
            "Привет, мир! 😀 ẞ €".encodeToByteArray(),
            bytes(0xE0, 0x80, 0xF0, 0x90, 0x41, 0xED, 0xA0, 0x80, 0xF4, 0x90, 0x80, 0x80, 0xE2, 0x82),
            ByteArray(2000) { random.nextInt(256).toByte() },
            ByteArray(2000) { listOf(0x41, 0x80, 0xBF, 0xC2, 0xE0, 0xED, 0xF0, 0xF4, 0xA0, 0x90)[random.nextInt(10)].toByte() }
        )
        val decoders = listOf<() -> ByteToCharDecoder>(
            { Utf8Decoder() },
            { Utf16Decoder(Utf16Decoder.Mode.DETECT) },
            { Utf16Decoder(Utf16Decoder.Mode.LITTLE_ENDIAN) },
            { SingleByteDecoder(SingleByteTables.KOI8_R) }
        )
        for (newDecoder in decoders) {
            for (input in samples) {
                val whole = newDecoder().decodeAll(input)
                repeat(20) {
                    val decoder = newDecoder()
                    val out = StringBuilder()
                    var position = 0
                    while (position < input.size) {
                        val length = minOf(input.size - position, random.nextInt(0, 6))
                        decoder.decode(input, position, length, out)
                        position += length
                    }
                    decoder.flush(out)
                    assertEquals(whole, out.toString())
                }
                // A decoder is reusable after flush.
                val reused = newDecoder()
                reused.decodeAll(bytes(0xE2))
                assertEquals(whole, reused.decodeAll(input))
            }
        }
        assertTrue(Utf8Decoder().decodeAll(samples[1]).all { it == '�' || it == 'A' })
    }
}
