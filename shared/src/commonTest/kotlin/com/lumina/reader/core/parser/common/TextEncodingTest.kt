package com.lumina.reader.core.parser.common

import com.lumina.reader.core.text.TestEncoders.bytes
import com.lumina.reader.core.text.TestEncoders.koi8r
import com.lumina.reader.core.text.TestEncoders.utf16
import com.lumina.reader.core.text.TestEncoders.windows1251
import com.lumina.reader.core.text.charset.TextCharset
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextEncodingTest {

    private val cp1251 = assertNotNull(TextCharset.forName("windows-1251"))

    @Test
    fun encodingDetection() {
        assertTrue(TextEncoding.isValidUtf8("😀 Привет".encodeToByteArray()))
        assertFalse(TextEncoding.isValidUtf8(windows1251("Привет")))
        assertFalse(TextEncoding.isValidUtf8(bytes(0xC0, 0x80))) // overlong
        assertEquals(
            cp1251,
            TextEncoding.declaredCharset("<?xml version=\"1.0\" encoding=\"windows-1251\"?><a/>".encodeToByteArray())
        )
        assertEquals("Тест", TextEncoding.decode(windows1251("Тест")))
        assertEquals("Тест", TextEncoding.decode(bytes(0xEF, 0xBB, 0xBF) + "Тест".encodeToByteArray()))

        // A stray byte inside a UTF-8 text does not turn the whole document into windows-1251.
        val mostly = "Привет, мир! ".repeat(50).encodeToByteArray() + bytes(0xFF) + "Ещё".encodeToByteArray()
        assertFalse(TextEncoding.isValidUtf8(mostly))
        assertTrue(TextEncoding.looksLikeUtf8(mostly))
        assertTrue(TextEncoding.decode(mostly).startsWith("Привет, мир!"))
        assertTrue(TextEncoding.decode(mostly).endsWith("�Ещё"))
        assertFalse(TextEncoding.looksLikeUtf8(windows1251("Привет, мир! ".repeat(50))))
    }

    @Test
    fun declarationsUseCharsetNamesAndAliases() {
        assertEquals(TextEncoding.WINDOWS_1251, cp1251)
        assertEquals("windows-1251", cp1251.name)
        assertEquals(cp1251, TextEncoding.charsetOrNull(" CP1251 "))
        assertEquals(
            TextCharset.forName("KOI8-R"),
            TextEncoding.declaredCharset("<?xml version='1.0' encoding='koi8-r'?><a/>".encodeToByteArray())
        )
        assertEquals(
            TextCharset.forName("IBM866"),
            TextEncoding.declaredCharset("<html><head><meta charset=cp866></head>".encodeToByteArray())
        )
        assertEquals(TextCharset.UTF_8, TextEncoding.declaredCharset("<?xml version=\"1.0\" encoding=\"utf8\"?>".encodeToByteArray()))
        assertNull(TextEncoding.declaredCharset("<?xml version='1.0' encoding='x-no-such-charset'?>".encodeToByteArray()))
        assertNull(TextEncoding.charsetOrNull("x-no-such-charset"))
        assertNull(TextEncoding.charsetOrNull("   "))
        assertNull(TextEncoding.charsetOrNull(null))
        assertEquals(TextCharset.UTF_16LE, TextEncoding.declaredCharset(bytes(0x3C, 0, 0x3F, 0)))
        assertEquals(TextCharset.UTF_16BE, TextEncoding.declaredCharset(bytes(0, 0x3C, 0, 0x3F)))
        assertNull(TextEncoding.detectBom(bytes(0xEF, 0xBB)))
        assertEquals(2, TextEncoding.detectBom(bytes(0xFF, 0xFE, 0x41))?.length)
    }

    @Test
    fun decodesTheFormsBooksArriveIn() {
        val text = "<?xml version=\"1.0\"?><p>Съешь же ещё этих мягких французских булок, да выпей чаю</p>"
        assertEquals(text, TextEncoding.decode(text.encodeToByteArray()))
        assertEquals(text, TextEncoding.decode(windows1251(text)))
        val koi = "<?xml version=\"1.0\" encoding=\"KOI8-R\"?><p>Привет, ёлка</p>"
        assertEquals(koi, TextEncoding.decode(koi8r(koi)))
        assertEquals(text, TextEncoding.decode(bytes(0xFE, 0xFF) + utf16(text, bigEndian = true)))
        assertEquals(text, TextEncoding.decode(bytes(0xFF, 0xFE) + utf16(text, bigEndian = false)))
        // BOM-less UTF-16 is recognised by its "<?" pattern.
        assertEquals(text, TextEncoding.decode(utf16(text, bigEndian = false)))
        assertEquals(text, TextEncoding.decode(utf16(text, bigEndian = true)))
        assertEquals("", TextEncoding.decode(ByteArray(0)))
        // A BOM that follows the detected charset's own decoding is dropped too.
        assertEquals("x", TextEncoding.decode(bytes(0xEF, 0xBB, 0xBF, 0x78)))
    }

    @Test
    fun streamingDetectionAgreesWithInMemoryDetection() {
        val longUtf8 = "Глава. ".repeat(20_000).encodeToByteArray()
        val longCp1251 = windows1251("Глава. ".repeat(20_000))
        // Valid UTF-8 head, legacy bytes only far beyond the 4 KB head and the first 64 KB chunk.
        val lateCp1251 = "a".repeat(70_000).encodeToByteArray() + windows1251("Привет, мир! ".repeat(400))
        val inputs = listOf(
            longUtf8, longCp1251, lateCp1251, ByteArray(0),
            "<?xml version='1.0' encoding='koi8-r'?>".encodeToByteArray() + koi8r("Привет"),
            bytes(0xEF, 0xBB, 0xBF) + "x".encodeToByteArray()
        )
        for (input in inputs) {
            assertEquals(TextEncoding.detect(input), TextEncoding.detect({ Buffer().write(input) }), "${input.size} bytes")
        }
        assertEquals(cp1251, TextEncoding.detect({ Buffer().write(lateCp1251) }))
        // With a UTF-16 mark the stream is read by the BOM-consuming UTF-16 decoder.
        assertEquals(TextCharset.UTF_16, TextEncoding.detect({ Buffer().write(bytes(0xFF, 0xFE, 0x3C, 0)) }))
        assertEquals(
            TextCharset.UTF_8,
            TextEncoding.detect({ Buffer().write(ByteArray(0)) }, fallback = TextCharset.UTF_8)
        )
    }
}
